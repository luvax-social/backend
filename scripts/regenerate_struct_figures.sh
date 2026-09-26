#!/usr/bin/env bash
#
# Regenerates every mechanically derivable figure that the backend structure documents quote.
#
# It prints Markdown fragments to stdout and writes nothing.
# Paste a section into `.claude/rules/struct.md` (and its `.agents/rules/struct.md` mirror) and
# into the root aggregator's `.claude/rules/STRUCT.md` when a figure has moved.
#
# It reads `git ls-files`, so an untracked or generated file is never counted as source.
# It does not update any document by itself, and it cannot check a prose claim - only a count,
# a list, a path, or a declared name.
#
# Usage:
#   ./scripts/regenerate_struct_figures.sh              # every section
#   ./scripts/regenerate_struct_figures.sh migrations   # one section
#
# Sections: migrations, modules, tests, rabbit, redis, enums, versions

set -euo pipefail

cd "$(dirname "$0")/.."

section_migrations() {
    echo "## Migrations"
    echo

    local sql_count first last
    sql_count=$(git ls-files 'src/main/resources/db/migration/*.sql' | wc -l | tr -d ' ')
    first=$(git ls-files 'src/main/resources/db/migration/*.sql' |
        sed 's#.*/V##; s#__.*##' | sort -n | head -1)
    last=$(git ls-files 'src/main/resources/db/migration/*.sql' |
        sed 's#.*/V##; s#__.*##' | sort -n | tail -1)

    echo "Count: ${sql_count}"
    echo "Range: V${first} to V${last}"

    local gaps
    gaps=$(git ls-files 'src/main/resources/db/migration/*.sql' |
        sed 's#.*/V##; s#__.*##' | sed 's/^0*//' | sort -n |
        awk -v last="$(echo "${last}" | sed 's/^0*//')" '
            { seen[$1] = 1 }
            END { for (i = 1; i <= last; i++) if (!(i in seen)) printf "V%d ", i }')
    if [ -n "${gaps}" ]; then
        echo "Gaps: ${gaps}"
    else
        echo "Gaps: none - every number in the range exists"
    fi

    local conf_count
    conf_count=$(git ls-files 'src/main/resources/db/migration/*.sql.conf' | wc -l | tr -d ' ')
    echo "Sidecars (\`.sql.conf\`, \`executeInTransaction=false\`): ${conf_count}"
    echo -n "Sidecar migrations: "
    git ls-files 'src/main/resources/db/migration/*.sql.conf' |
        sed 's#.*/V##; s#__.*##' | sort -n | sed 's/^/V/' | tr '\n' ' '
    echo
    echo

    echo "| Migration | Description |"
    echo "|-----------|-------------|"
    git ls-files 'src/main/resources/db/migration/*.sql' |
        sed 's#.*/##; s#\.sql$##' |
        sort -t V -k2 -n |
        awk -F'__' '{ printf "| %s | %s |\n", $1, $2 }'
    echo
}

section_modules() {
    echo "## Modules"
    echo

    local module_count
    module_count=$(git ls-files 'src/main/java/com/app/modules/*' |
        sed 's#src/main/java/com/app/modules/##; s#/.*##' | sort -u | wc -l | tr -d ' ')
    echo "Domain modules under \`src/main/java/com/app/modules/\`: ${module_count}"
    echo -n "Modules: "
    git ls-files 'src/main/java/com/app/modules/*' |
        sed 's#src/main/java/com/app/modules/##; s#/.*##' | sort -u | tr '\n' ' '
    echo
    echo

    local rules_count
    rules_count=$(git ls-files 'docs/modules/*/DATA_RULES.md' | wc -l | tr -d ' ')
    echo "\`docs/modules/{module}/DATA_RULES.md\` files: ${rules_count}"
    echo -n "Modules without a DATA_RULES.md: "
    comm -23 \
        <(git ls-files 'src/main/java/com/app/modules/*' |
            sed 's#src/main/java/com/app/modules/##; s#/.*##' | sort -u) \
        <(git ls-files 'docs/modules/*/DATA_RULES.md' |
            sed 's#docs/modules/##; s#/DATA_RULES.md##' | sort) |
        tr '\n' ' '
    echo
    echo

    echo "### Sub-packages per module"
    echo
    echo "| Module | Sub-packages |"
    echo "|--------|--------------|"
    local module subpkgs
    for module in $(git ls-files 'src/main/java/com/app/modules/*' |
        sed 's#src/main/java/com/app/modules/##; s#/.*##' | sort -u); do
        subpkgs=$(git ls-files "src/main/java/com/app/modules/${module}/*" |
            sed "s#src/main/java/com/app/modules/${module}/##; s#/[^/]*\.java\$##" |
            grep -v '\.java$' | sort -u | tr '\n' ', ' | sed 's/,$//; s/,/, /g')
        printf "| \`%s\` | %s |\n" "${module}" "${subpkgs}"
    done
    echo

    echo "### \`common/\` sub-packages"
    echo
    git ls-files 'src/main/java/com/app/common/*' |
        sed 's#src/main/java/com/app/common/##; s#/[^/]*\.java$##' |
        grep -v '\.java$' | sort -u | sed 's#^#- `common/#; s#$#/`#'
    echo
}

section_tests() {
    echo "## Test classes"
    echo

    local total
    total=$(git ls-files 'src/test/java/**/*.java' | wc -l | tr -d ' ')
    echo "Total test classes: ${total}"
    echo

    echo "| Package | Test Classes |"
    echo "|---------|-------------|"
    git ls-files 'src/test/java/**/*.java' |
        sed 's#src/test/java/com/app/##' |
        awk -F'/' '{
            cls = $NF
            sub(/\.java$/, "", cls)
            if (NF == 1) { pkg = "(root)" } else { pkg = $1; for (i = 2; i < NF; i++) pkg = pkg "/" $i }
            classes[pkg] = classes[pkg] (classes[pkg] == "" ? "" : ", ") "`" cls "`"
        }
        END { for (p in classes) printf "%s\t%s\n", p, classes[p] }' |
        sort |
        awk -F'\t' '{ printf "| `%s` | %s |\n", $1, $2 }'
    echo
}

section_rabbit() {
    local topology='src/main/java/com/app/common/config/rabbit/RabbitMqTopologyConfig.java'

    # A constant declaration may wrap onto the following line, so any line ending in `=` is
    # joined to its successor before a constant is resolved. Without this, every wrapped
    # declaration reads as absent and the queue table silently comes up short.
    local joined
    joined=$(sed ':a;/=$/{N;s/\n[[:space:]]*/ /;ba}' "${topology}")

    resolve() {
        printf '%s\n' "${joined}" |
            grep -oE "String $1 = \"[^\"]+\"" | head -1 | sed 's/.*"\(.*\)"/\1/'
    }

    echo "## RabbitMQ topology"
    echo
    echo "Declared in \`${topology}\`; module bindings live with their module."
    echo

    echo "### Exchanges"
    echo
    echo "| Exchange | Type |"
    echo "|----------|------|"
    grep -oE 'ExchangeBuilder\.(topic|fanout|direct|headers)Exchange\([A-Z_]+\)' "${topology}" |
        sed 's/ExchangeBuilder\.//; s/Exchange(/ /; s/)//' |
        while read -r kind constant; do
            printf "| \`%s\` | %s |\n" "$(resolve "${constant}")" "${kind}"
        done
    echo

    echo "### Durable queues"
    echo
    echo "| Queue |"
    echo "|-------|"
    grep -oE 'QueueBuilder\.durable\([A-Z_]+\)' "${topology}" |
        sed 's/QueueBuilder\.durable(//; s/)//' |
        while read -r constant; do
            printf "| \`%s\` |\n" "$(resolve "${constant}")"
        done
    echo

    echo "### Queue-name constants declared but bound to no \`@Bean\`"
    echo
    local constant
    for constant in $(grep -oE 'String [A-Z_]*QUEUE\b' "${topology}" | awk '{print $2}' | sort -u); do
        if ! grep -q "durable(${constant})" "${topology}"; then
            printf -- "- \`%s\`\n" "$(resolve "${constant}")"
        fi
    done
    echo

    echo "### Consumers"
    echo
    echo "Classes carrying \`@RabbitListener\`: $(git grep -l '@RabbitListener' -- 'src/main/java' | wc -l | tr -d ' ')"
    echo "Annotated methods: $(git grep -c '@RabbitListener' -- 'src/main/java' | awk -F: '{ s += $2 } END { print s }')"
    echo
    git grep -l '@RabbitListener' -- 'src/main/java' | sed 's#^#- `#; s#$#`#'
    echo

    echo "### Files declaring bindings"
    echo
    git grep -l 'BindingBuilder' -- 'src/main/java' | sed 's#^#- `#; s#$#`#'
    echo
}

section_redis() {
    echo "## Redis key prefixes"
    echo
    echo "Every colon-separated string literal declared in \`src/main/java\` that looks like a key"
    echo "prefix, with the file that declares it. Verify the TTL by reading the declaring class;"
    echo "a TTL is not mechanically derivable from the literal."
    echo
    echo "| Key literal | Declared in |"
    echo "|-------------|-------------|"
    git grep -hoE '"[a-z][a-z0-9-]*(:[a-zA-Z0-9{}_.%-]+)+:?"' -- 'src/main/java' |
        sort -u |
        grep -vE '^"java:' |
        while read -r literal; do
            local bare owner
            bare=${literal%\"}
            bare=${bare#\"}
            owner=$(git grep -l -F -- "${literal}" -- 'src/main/java' | head -1 | sed 's#.*/##')
            printf "| \`%s\` | \`%s\` |\n" "${bare}" "${owner}"
        done
    echo
}

section_enums() {
    echo "## PostgreSQL enum types"
    echo
    echo "Every \`CREATE TYPE ... AS ENUM\` and every \`ALTER TYPE ... ADD VALUE\` in the migration"
    echo "set, in migration order. The live set is the union; read them in order to build it."
    echo
    git ls-files 'src/main/resources/db/migration/*.sql' |
        sort -t V -k2 -n |
        while read -r file; do
            local hits
            hits=$(grep -inE "CREATE TYPE|ALTER TYPE .* ADD VALUE" "${file}" || true)
            if [ -n "${hits}" ]; then
                echo "### $(basename "${file}")"
                echo
                echo '```sql'
                echo "${hits}" | sed 's/^[0-9]*://'
                echo '```'
                echo
            fi
        done
}

section_versions() {
    echo "## Declared versions"
    echo
    echo "| What | Value | Source |"
    echo "|------|-------|--------|"

    printf "| Spring Boot | %s | \`pom.xml\` parent |\n" \
        "$(sed -n '/<parent>/,/<\/parent>/p' pom.xml | grep -oE '<version>[^<]+' | head -1 | cut -c10-)"
    printf "| Java | %s | \`pom.xml\` \`java.version\` |\n" \
        "$(grep -oE '<java\.version>[^<]+' pom.xml | cut -c15-)"

    local prop
    for prop in spring-cloud testcontainers mapstruct aws-sdk datasource-micrometer; do
        printf "| %s | %s | \`pom.xml\` property |\n" "${prop}" \
            "$(grep -oE "<${prop}\.version>[^<]+" pom.xml | sed "s/<${prop}\.version>//")"
    done

    echo "| Spotless | $(sed -n '/spotless-maven-plugin/,/<\/plugin>/p' pom.xml | grep -oE '<version>[^<]+' | head -1 | cut -c10-) | \`pom.xml\` plugin |"
    echo

    echo "### docker-compose images"
    echo
    grep -E '^\s+(image|build):' docker-compose.yaml | sed 's/^\s*/- /'
    echo

    echo "### CI workflows"
    echo
    git ls-files '.github/workflows/*' | sed 's#^#- `#; s#$#`#'
    echo

    echo "### pr-lint allowlisted scopes"
    echo
    sed -n '/scopes: |/,/subjectPattern/p' .github/workflows/pr-lint.yml |
        sed '1d; $d' | sed '/^\s*#/d' | tr -d ' ' | grep -v '^$' | tr '\n' ' '
    echo
    echo
}

main() {
    local sections=("$@")
    if [ ${#sections[@]} -eq 0 ]; then
        sections=(migrations modules tests rabbit redis enums versions)
    fi

    echo "# Backend structure figures"
    echo
    echo "Generated by \`scripts/regenerate_struct_figures.sh\` from \`git ls-files\` at"
    echo "\`$(git rev-parse --short HEAD)\` on $(date -u '+%Y-%m-%d')."
    echo

    local section
    for section in "${sections[@]}"; do
        "section_${section}"
    done
}

main "$@"
