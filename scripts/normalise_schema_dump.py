#!/usr/bin/env python3
"""Rewrites a ``pg_dump --schema-only`` file into the shape ``database/schema.sql`` uses.

The dump is authoritative for what the schema *is*; the existing file is authoritative for how
that schema is *presented* and for the commentary attached to it.  This script keeps both: it
reads every object out of the dump, and it lifts the comments out of the previous
``database/schema.sql`` and re-attaches them to whichever table or column still exists.

What it changes about the dump:

- drops the ``public.`` qualifier, the psql meta-commands and the ``SET`` preamble
- restores the short type spellings the file uses (``VARCHAR``, ``TIMESTAMPTZ``, ``UUID``)
- folds ``ALTER TABLE ONLY ... ADD CONSTRAINT`` back into the table body
- aligns column name, type and modifiers into the three columns the file has always used
- groups tables under the module banners the file has always used
- drops ``flyway_schema_history`` and the dated ``user_events_YYYY_MM`` partitions, because
  neither is schema a reader should treat as declared: one is Flyway's bookkeeping and the
  others are created at runtime by ``UserEventsPartitionJob``

Usage:
    normalise_schema_dump.py <raw-dump.sql> <previous-schema.sql> <migration-version>
"""

from __future__ import annotations

import re
import sys
from collections import OrderedDict

# Which module banner each table is emitted under.  A table absent from this map lands in
# UNGROUPED at the end of the file, which is the signal that this map needs a new entry.
MODULES: "OrderedDict[str, list[str]]" = OrderedDict(
    [
        ("AUTH", ["users", "user_credentials", "oauth_accounts", "refresh_tokens"]),
        ("USER PROFILE & SETTINGS", ["user_settings", "push_tokens"]),
        ("SOCIAL GRAPH (Follow / Block)", ["follows", "blocks"]),
        ("MEDIA ASSETS (Cloudflare R2)", ["media_assets"]),
        (
            "POST",
            [
                "posts",
                "post_media",
                "post_likes",
                "post_saves",
                "post_views",
                "post_edit_history",
            ],
        ),
        (
            "COMMENTS (Nested, Adjacency List + root_id + depth)",
            ["comments", "comment_likes", "comment_write_idempotency"],
        ),
        (
            "HASHTAG & TRENDING",
            ["hashtags", "post_hashtags", "hashtag_trending"],
        ),
        ("STORY (24-hour Ephemeral)", ["stories", "story_views", "story_likes"]),
        ("NOTIFICATIONS", ["notifications"]),
        (
            "DIRECT MESSAGE / CHAT",
            [
                "conversations",
                "conversation_participants",
                "messages",
                "message_write_idempotency",
                "archived_group_conversations",
                "archived_group_participants",
                "archived_group_messages",
            ],
        ),
        ("REPORT (Content Flagging)", ["reports"]),
        (
            "METADATA CONFIG",
            [
                "system_settings",
                "notification_type_configs",
                "report_reason_configs",
                "moderation_action_configs",
                "feature_flags",
                "support_category_configs",
                "verification_categories",
            ],
        ),
        (
            "ADMIN (Moderation Audit Log)",
            [
                "admin_actions",
                "user_warnings",
                "user_strikes",
                "user_suspensions",
                "platform_stats",
            ],
        ),
        (
            "SUPPORT (Tickets, Appeals, Verification)",
            [
                "support_tickets",
                "user_verifications",
                "verification_requests",
            ],
        ),
        (
            "MAIL",
            [
                "email_deliveries",
                "mail_templates",
                "mail_campaigns",
                "mail_campaign_recipients",
            ],
        ),
        (
            "INTEREST TAXONOMY",
            ["categories", "user_interests", "post_categories", "post_user_tags"],
        ),
        (
            "RECOMMENDATION SYSTEM & USER BEHAVIOR",
            [
                "user_events",
                "user_events_default",
                "post_interaction_scores",
                "user_similarity",
                "user_hashtag_affinity",
                "user_suggestions",
                "suggestion_dismissals",
            ],
        ),
        ("COMMON / OUTBOX", ["outbox_events", "processed_messages"]),
    ]
)

# Objects the dump carries that are not declared schema a reader should rely on.
EXCLUDED_TABLES = {"flyway_schema_history"}
RUNTIME_PARTITION = re.compile(r"^user_events_\d{4}_\d{2}$")
# A partition child repeats its parent's columns, so it is emitted in the PARTITIONS section as a
# `PARTITION OF` declaration rather than as a table of its own.
PARTITION_CHILDREN = {"user_events_default"}

# The generator writes this above each table's indexes. The loader recognises it and drops
# it rather than lifting it as commentary, which is what keeps regeneration idempotent.
INDEX_GROUP_HEADER = "-- indexes on %s"
INDEX_GROUP_HEADER_RE = re.compile(r"^-- indexes on [a-z_0-9]+$")

# Every comment line the generator writes itself. The loader skips these, so regenerating over a
# previously generated file does not lift the generator's own prose back in as commentary and
# accumulate a copy on every run.
GENERATED_LINES: "set[str]" = set()


def generated(text: str) -> str:
    """Registers a generator-authored comment block and returns it.

    A bare ``--`` is never registered: it carries no text, it appears inside several of these
    preambles, and it is also the separator between carried-forward notes, so registering it
    would make the loader swallow those separators and let the section reflow on every run.
    """
    for line in text.splitlines():
        stripped = line.strip()
        if stripped and stripped != "--":
            GENERATED_LINES.add(stripped)
    return text


HEADER_PREAMBLE = """-- Generated by `scripts/regenerate_schema_sql.sh` from a `pg_dump --schema-only` of a
-- clean database with Flyway migrations V01 to V{version} applied; it is a reference
-- rendering of the final-state schema and is never applied by Flyway.
--
-- Do not hand-edit the structure. Correct a migration and regenerate.
-- Explanatory comments are carried forward from the previous revision of this file and
-- are the one part a person maintains by hand.
--
-- `flyway_schema_history` and the dated `user_events_YYYY_MM` partitions are omitted:
-- the first is Flyway's own bookkeeping and the others are created at runtime by
-- `UserEventsPartitionJob`, so neither is schema a reader should treat as declared.
"""

FORWARD_FK_PREAMBLE = generated(
    """-- These reference a table declared later under a different module banner, so they
-- are attached here rather than inline. Grouping by module is what puts them out of
-- order; the constraint itself is no different from an inline one.
"""
)

OBJECT_COMMENT_PREAMBLE = generated(
    """-- `COMMENT ON` text stored in the database itself, written by the migrations. It is
-- part of the schema, so it is reproduced verbatim rather than paraphrased.
"""
)

CARRIED_FORWARD_PREAMBLE = generated(
    """-- Commentary from the previous revision of this file that the regeneration could not
-- place, because the table, column, index or routine it sat above no longer exists
-- under that name. It is kept here rather than dropped; delete a note once it has
-- been confirmed obsolete, and move it back inline if its anchor returns.
"""
)

# The header's second line carries the migration version, so it is matched by shape rather than
# by text; every other header line is static and registered above.
generated(HEADER_PREAMBLE)
HEADER_VERSION_LINE = re.compile(
    r"^-- clean database with Flyway migrations V01 to V\d+ applied; it is a reference$"
)

TYPE_REWRITES = [
    (re.compile(r"\bcharacter varying\b"), "VARCHAR"),
    (re.compile(r"\bcharacter\b(?!\s+varying)"), "CHAR"),
    (re.compile(r"\btimestamp with time zone\b"), "TIMESTAMPTZ"),
    (re.compile(r"\btimestamp without time zone\b"), "TIMESTAMP"),
    (re.compile(r"\bdouble precision\b"), "DOUBLE PRECISION"),
    (re.compile(r"\b(uuid|text|boolean|integer|bigint|smallint|numeric|jsonb|json|inet|date|real|bytea|interval)\b"),
     lambda m: m.group(1).upper()),
]

DEFAULT_REWRITES = [
    (re.compile(r"\bnow\(\)"), "NOW()"),
    (re.compile(r"\bCURRENT_TIMESTAMP\b"), "NOW()"),
    # pg_dump spells an enum default with an explicit cast the column type already implies.
    (re.compile(r"DEFAULT ('(?:[^']|'')*')::[a-z_0-9]+\b"), r"DEFAULT \1"),
    (re.compile(r"DEFAULT false\b"), "DEFAULT FALSE"),
    (re.compile(r"DEFAULT true\b"), "DEFAULT TRUE"),
]

# pg_dump emits `DEFAULT x NOT NULL`; this file has always written `NOT NULL DEFAULT x`.
# It never fires on a named NOT NULL constraint, where the name binds to the NOT NULL and moving
# the words apart would not parse.
MODIFIER_ORDER = re.compile(r"^(DEFAULT (?:(?!CONSTRAINT ).)+?) NOT NULL$")


def strip_schema(text: str) -> str:
    return text.replace("public.", "")


QUOTED = re.compile(r"'(?:[^']|'')*'")
SENTINEL = re.compile(r"@@LIT(\d+)@@")


def outside_strings(text: str, apply) -> str:
    """Runs ``apply`` over the text with every single-quoted literal masked out.

    A type rewrite must never reach inside a literal: an enum value spelled `'text'` and the
    type named `text` are the same word, and uppercasing the value silently changes the schema.
    """
    literals = []

    def stash(match):
        literals.append(match.group(0))
        return "@@LIT%d@@" % (len(literals) - 1)

    masked = apply(QUOTED.sub(stash, text))
    return SENTINEL.sub(lambda m: literals[int(m.group(1))], masked)


def rewrite_types(text: str) -> str:
    def apply(value: str) -> str:
        for pattern, repl in TYPE_REWRITES:
            value = pattern.sub(repl, value)
        return value

    return outside_strings(text, apply)


def rewrite_defaults(text: str) -> str:
    for pattern, repl in DEFAULT_REWRITES:
        text = pattern.sub(repl, text)
    return text


class Block:
    def __init__(self, name: str, kind: str, body: str) -> None:
        self.name = name
        self.kind = kind
        self.body = body.strip()


def parse_blocks(dump: str) -> "list[Block]":
    """Splits a pg_dump into its ``-- Name: X; Type: Y`` blocks."""
    header = re.compile(
        r"^--\r?\n-- Name: (?P<name>.*?); Type: (?P<kind>[A-Z ]+); Schema: .*?; Owner: .*?\r?\n--\r?\n",
        re.MULTILINE,
    )
    matches = list(header.finditer(dump))
    blocks = []
    for index, match in enumerate(matches):
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(dump)
        blocks.append(Block(match.group("name"), match.group("kind"), dump[start:end]))
    return blocks


def table_of(block: Block) -> str:
    """The table a constraint, index or trigger block belongs to."""
    match = re.search(r"ON(?: ONLY)? (?:public\.)?([a-z_0-9]+)", block.body)
    if match:
        return match.group(1)
    match = re.search(r"ALTER TABLE(?: ONLY)? (?:public\.)?([a-z_0-9]+)", block.body)
    return match.group(1) if match else ""


def is_excluded(table: str) -> bool:
    return (
        table in EXCLUDED_TABLES
        or table in PARTITION_CHILDREN
        or bool(RUNTIME_PARTITION.match(table))
    )


def split_columns(body: str) -> "tuple[str, list[str], str]":
    """Splits ``CREATE TABLE x (\\n cols\\n) tail;`` into head, column lines, tail."""
    open_paren = body.index("(")
    depth = 0
    for position, character in enumerate(body[open_paren:], start=open_paren):
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
            if depth == 0:
                close_paren = position
                break
    head = body[:open_paren].strip()
    inner = body[open_paren + 1 : close_paren]
    tail = body[close_paren + 1 :].strip().rstrip(";").strip()

    columns, current, depth = [], "", 0
    for character in inner:
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
        if character == "," and depth == 0:
            columns.append(current.strip())
            current = ""
        else:
            current += character
    if current.strip():
        columns.append(current.strip())
    return head, [c for c in columns if c], tail


COLUMN_SPLIT = re.compile(
    r"^(?P<name>\"?[a-z_0-9]+\"?)\s+(?P<type>(?:VARCHAR|CHAR)\(\d+\)|[A-Za-z_][A-Za-z_ ]*?(?:\[\])?(?:\(\d+(?:,\s*\d+)?\))?)(?P<rest>\s+.*)?$"
)


def format_column(column: str, name_width: int, type_width: int) -> str:
    match = COLUMN_SPLIT.match(column)
    if not match:
        return "    " + column
    name = match.group("name")
    column_type = match.group("type").strip()
    rest = (match.group("rest") or "").strip()
    reordered = MODIFIER_ORDER.match(rest)
    if reordered:
        rest = "NOT NULL " + reordered.group(1)
    line = f"    {name.ljust(name_width)} {column_type.ljust(type_width)}"
    return (line + " " + rest).rstrip() if rest else line.rstrip()


def constraint_text(block: Block) -> str:
    """Turns an ``ALTER TABLE ONLY ... ADD CONSTRAINT`` block into an inline table constraint."""
    body = " ".join(block.body.split())
    match = re.search(r"ADD CONSTRAINT (?P<name>[a-z_0-9]+) (?P<definition>.*?);", body)
    if not match:
        return ""
    name = match.group("name")
    definition = match.group("definition").strip()
    # A system-style name adds nothing a reader does not already see in the definition.
    if re.fullmatch(r"[a-z_0-9]+_(pkey|key|fkey|check)\d*", name):
        return definition
    return f"CONSTRAINT {name} {definition}"


class PreviousComments:
    """The commentary lifted out of the previous revision of ``database/schema.sql``.

    A dump carries no prose, so this is the one part of the file a person writes and the one part
    that has to survive a regeneration.  Every block is tracked, and any block that finds no
    anchor in the new schema is reported rather than dropped, because a comment nobody can place
    is still knowledge someone wrote down.
    """

    def __init__(self) -> None:
        self.table: "dict[str, list[str]]" = {}
        self.column: "dict[str, list[str]]" = {}
        self.index: "dict[str, list[str]]" = {}
        # Functions, triggers and views, keyed by name; the dump carries no prose for any of them.
        self.routine: "dict[str, list[str]]" = {}
        self.unanchored: "list[list[str]]" = []
        self._used: "set[int]" = set()
        self._blocks: "list[list[str]]" = []

    def _record(self, block: "list[str]") -> "list[str]":
        self._blocks.append(block)
        return block

    def claim(self, block: "list[str]") -> None:
        self._used.add(id(block))

    def leftovers(self) -> "list[list[str]]":
        return [b for b in self._blocks if id(b) not in self._used]


def load_previous_comments(previous: str) -> PreviousComments:
    """Lifts the previous file's commentary out, keyed by table, column and index name.

    A block sitting above a ``CREATE TABLE`` belongs to the table, one above a column line inside
    a table body belongs to that column, and one above a ``CREATE INDEX`` belongs to that index.
    A blank line inside a block does not end it: the previous file separates a multi-line design
    note from the statement it describes with one, and treating that as a terminator silently
    discarded the longest comments in the file.
    """
    found = PreviousComments()

    pending: "list[str]" = []
    current_table = None
    in_banner = False
    in_body = False

    for raw in previous.splitlines():
        stripped = raw.strip()

        # A comment inside a dollar-quoted function body explains the routine's own code and
        # travels with it in the dump. Reading it here would lift it out of the body and strand
        # it as an unplaceable note.
        if "$$" in stripped:
            in_body = stripped.count("$$") % 2 == 1 and not in_body
            continue
        if in_body:
            continue

        # A banner is exactly three lines: a rule, the section title, and a closing rule. The
        # opening rule ends whatever came before it, and that block is kept as unanchored rather
        # than discarded, because a note written at the foot of a section is still a note
        # somebody wrote. The title between the two rules is structure, not commentary.
        if stripped.startswith("-- ==="):
            if in_banner:
                in_banner = False
            else:
                if pending:
                    found.unanchored.append(found._record(pending))
                pending = []
                in_banner = True
            continue
        if not stripped:
            continue

        if stripped.startswith("--"):
            if in_banner or INDEX_GROUP_HEADER_RE.match(stripped):
                continue
            if stripped in GENERATED_LINES or HEADER_VERSION_LINE.match(stripped):
                continue
            # A bare `--` with nothing collected before it is the spacer inside a generated
            # preamble whose text lines were just skipped, not the separator between two notes.
            if stripped == "--" and not pending:
                continue
            pending.append(stripped)
            continue

        create_table = re.match(r"CREATE TABLE ([a-z_0-9]+)", stripped)
        if create_table:
            current_table = create_table.group(1)
            if pending:
                found.table[current_table] = found._record(pending)
            pending = []
            continue

        create_index = re.match(
            r"CREATE (?:UNIQUE )?INDEX (?:CONCURRENTLY )?(?:IF NOT EXISTS )?([a-z_0-9]+)", stripped
        )
        if create_index:
            if pending:
                found.index[create_index.group(1)] = found._record(pending)
            pending = []
            current_table = None
            continue

        routine = re.match(
            r"CREATE (?:OR REPLACE )?(?:FUNCTION|TRIGGER|VIEW|MATERIALIZED VIEW) ([a-z_0-9]+)",
            stripped,
        )
        if routine:
            if pending:
                found.routine[routine.group(1)] = found._record(pending)
            pending = []
            current_table = None
            continue

        if current_table:
            if stripped.startswith(")"):
                current_table = None
                pending = []
                continue
            column = re.match(r"([a-z_0-9]+)\s+\S", stripped)
            if column and pending:
                found.column[f"{current_table}.{column.group(1)}"] = found._record(pending)
            pending = []
            continue

        if pending:
            found.unanchored.append(found._record(pending))
        pending = []

    if pending:
        found.unanchored.append(found._record(pending))

    return found


def banner(title: str) -> str:
    rule = "-- " + "=" * 60
    return f"{rule}\n-- {title}\n{rule}\n"


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2

    dump_path, previous_path, version = sys.argv[1], sys.argv[2], sys.argv[3]
    dump = open(dump_path, encoding="utf-8").read()
    previous = open(previous_path, encoding="utf-8").read()

    prose = load_previous_comments(previous)
    blocks = parse_blocks(dump)

    extensions, enums, functions, views, comments = [], [], [], [], []
    tables: "OrderedDict[str, Block]" = OrderedDict()
    constraints: "dict[str, list[str]]" = {}
    indexes: "dict[str, list[str]]" = {}
    triggers: "dict[str, list[str]]" = {}
    partitions: "list[str]" = []

    for block in blocks:
        body = rewrite_defaults(rewrite_types(strip_schema(block.body))).strip()

        if block.kind == "EXTENSION":
            if body.startswith("CREATE EXTENSION"):
                extensions.append(body)
        elif block.kind == "TYPE":
            enums.append(body)
        elif block.kind == "FUNCTION":
            functions.append(body)
        elif block.kind == "VIEW":
            views.append(body)
        elif block.kind == "TABLE":
            name = block.name.split()[0]
            if not is_excluded(name):
                tables[name] = Block(name, block.kind, body)
        elif block.kind in ("CONSTRAINT", "FK CONSTRAINT"):
            owner = table_of(block)
            if not is_excluded(owner):
                text = constraint_text(Block(block.name, block.kind, body))
                if text:
                    constraints.setdefault(owner, []).append(text)
        elif block.kind == "INDEX":
            owner = table_of(block)
            if not is_excluded(owner):
                # pg_dump declares an index on a partitioned table as `ON ONLY parent`, then
                # repeats it on every child and reattaches each one. The child copies and the
                # ATTACH statements are dropped here, so the `ONLY` has to go too: without it the
                # parent index is a shell that never reaches a partition.
                indexes.setdefault(owner, []).append(
                    re.sub(r"\bON ONLY ", "ON ", body)
                )
        elif block.kind == "TRIGGER":
            owner = table_of(block)
            if not is_excluded(owner):
                triggers.setdefault(owner, []).append(body)
        elif block.kind == "COMMENT":
            # `COMMENT ON EXTENSION` is the extension's own prose from its installer, not this
            # project's; everything else is domain knowledge attached to a type, table or column.
            if body.startswith("COMMENT ON") and not body.startswith("COMMENT ON EXTENSION"):
                comments.append(body)
        elif block.kind == "TABLE ATTACH":
            match = re.search(
                r"ALTER TABLE ONLY ([a-z_0-9]+) ATTACH PARTITION ([a-z_0-9]+) (.*);", body
            )
            if match and match.group(2) in PARTITION_CHILDREN:
                parent, child, bounds = match.groups()
                partitions.append(f"CREATE TABLE {child} PARTITION OF {parent} {bounds};")
        # INDEX ATTACH and COMMENT blocks describe partition plumbing and extension prose that
        # the file has never carried; they are reproduced by the migrations, not by this file.

    out: "list[str]" = []
    out.append(banner("PostgreSQL reference schema - GENERATED"))
    out.append(HEADER_PREAMBLE.format(version=version))

    out.append(banner("EXTENSIONS"))
    out.extend(extensions)
    out.append("")

    out.append(banner("ENUM TYPES"))
    out.extend(enums)
    out.append("")

    # A module grouping is for a reader, not for the planner, so a table sometimes references one
    # declared further down the file. Those foreign keys are held back and emitted at the end, so
    # the file both keeps its module order and replays top to bottom against an empty database.
    emit_order = [name for names in MODULES.values() for name in names if name in tables]
    emit_order += [name for name in tables if name not in emit_order]
    position = {name: index for index, name in enumerate(emit_order)}
    deferred: "list[tuple[str, str]]" = []

    def partition_constraints(owner: str) -> "list[str]":
        kept = []
        for text in constraints.get(owner, []):
            target = re.search(r"REFERENCES ([a-z_0-9]+)", text)
            if target and position.get(target.group(1), -1) > position.get(owner, -1):
                deferred.append((owner, text))
            else:
                kept.append(text)
        return kept

    resolved = {name: partition_constraints(name) for name in tables}

    placed = set()
    for title, table_names in MODULES.items():
        present = [name for name in table_names if name in tables]
        if not present:
            continue
        out.append(banner("MODULE: " + title))
        for name in present:
            placed.add(name)
            out.append(render_table(tables[name], resolved, prose))
        out.append("")

    leftover = [name for name in tables if name not in placed]
    if leftover:
        out.append(banner("UNGROUPED - add these to MODULES in normalise_schema_dump.py"))
        for name in leftover:
            out.append(render_table(tables[name], resolved, prose))
        out.append("")

    if deferred:
        out.append(banner("FORWARD-REFERENCING FOREIGN KEYS"))
        out.append(FORWARD_FK_PREAMBLE)
        for owner, text in deferred:
            out.append(f"ALTER TABLE {owner} ADD {text};")
        out.append("")

    if partitions:
        out.append(banner("PARTITIONS (declared, not runtime)"))
        out.extend(partitions)
        out.append("")

    out.append(banner("INDEXES"))
    for name in list(tables):
        statements = sorted(indexes.get(name, []))
        if not statements:
            continue
        out.append(INDEX_GROUP_HEADER % name)
        for statement in statements:
            match = re.match(
                r"CREATE (?:UNIQUE )?INDEX (?:CONCURRENTLY )?(?:IF NOT EXISTS )?([a-z_0-9]+)",
                statement,
            )
            index_prose = prose.index.get(match.group(1)) if match else None
            if index_prose:
                prose.claim(index_prose)
                out.extend(index_prose)
            out.append(statement)
        out.append("")

    def with_prose(statements: "list[str]") -> "list[str]":
        """Re-attaches the previous file's note above each function, trigger or view."""
        rendered = []
        for statement in statements:
            match = re.match(
                r"CREATE (?:OR REPLACE )?(?:FUNCTION|TRIGGER|VIEW|MATERIALIZED VIEW) ([a-z_0-9]+)",
                statement,
            )
            note = prose.routine.get(match.group(1)) if match else None
            if note:
                prose.claim(note)
                rendered.extend(note)
            rendered.append(statement + "\n")
        return rendered

    if functions:
        out.append(banner("FUNCTIONS"))
        out.extend(with_prose(functions))

    if triggers:
        out.append(banner("TRIGGERS"))
        for name in list(tables):
            statements = sorted(triggers.get(name, []))
            if statements:
                out.extend(with_prose(statements))

    if views:
        out.append(banner("VIEWS"))
        out.extend(with_prose(views))

    if comments:
        out.append(banner("OBJECT COMMENTS"))
        out.append(OBJECT_COMMENT_PREAMBLE)
        out.extend(c + "\n" for c in comments)

    orphans = prose.leftovers()
    if orphans:
        out.append(banner("NOTES CARRIED FORWARD"))
        out.append(CARRIED_FORWARD_PREAMBLE)
        # Separated by a bare `--` rather than a blank line: a blank line would split the section
        # into several blocks on the next run and the notes would drift apart a little each time.
        separated: "list[str]" = []
        for orphan in orphans:
            if separated:
                separated.append("--")
            separated.extend(orphan)
        out.append("\n".join(separated) + "\n")

    text = "\n".join(out)
    # pg_dump's CHECK bodies keep their parentheses exactly as it prints them. Stripping the
    # redundant outer pair reads better and is not worth it: the pattern that does it cannot tell
    # a redundant pair from a significant one, and a mis-stripped CHECK is a silently different
    # constraint.
    text = re.sub(r"\n{3,}", "\n\n", text)
    sys.stdout.write(text.rstrip() + "\n")
    return 0


def render_table(block, constraints, prose) -> str:
    head, columns, tail = split_columns(block.body)
    name = block.name

    lines = []
    block_comment = prose.table.get(name)
    if block_comment:
        prose.claim(block_comment)
        lines.extend(block_comment)
    lines.append(head + " (")

    parsed = []
    for column in columns:
        match = COLUMN_SPLIT.match(column)
        parsed.append((match.group("name"), match.group("type").strip()) if match else (None, None))

    name_width = max((len(n) for n, _ in parsed if n), default=0)
    type_width = max((len(t) for _, t in parsed if t), default=0)

    # Comments are emitted as their own lines and take no comma; only the member lines - the
    # columns and the table constraints - are comma-separated, and the last one carries none.
    members = []
    for column, (column_name, _) in zip(columns, parsed):
        column_prose = prose.column.get(f"{name}.{column_name}") if column_name else None
        if column_prose:
            prose.claim(column_prose)
        members.append((column_prose or [], format_column(column, name_width, type_width)))

    # pg_dump emits constraints in name order; a reader wants the key first and the checks last.
    order = {"PRIMARY KEY": 0, "UNIQUE": 1, "FOREIGN KEY": 2, "CHECK": 3, "EXCLUDE": 4}

    def constraint_rank(text: str) -> "tuple[int, str]":
        for keyword, rank in order.items():
            if keyword in text:
                return rank, text
        return 5, text

    for constraint in sorted(constraints.get(name, []), key=constraint_rank):
        members.append(([], "    " + constraint))

    for index, (comments, text) in enumerate(members):
        lines.extend("    " + comment for comment in comments)
        lines.append(text + ("," if index < len(members) - 1 else ""))

    lines.append(")" + (" " + tail if tail else "") + ";")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    raise SystemExit(main())
