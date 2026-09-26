#!/usr/bin/env python3
"""Reassigns every media_refs entry in content/posts.json across the manifest.

The problem it solves: media_refs were authored against a 150-asset manifest, so a handful
of assets carried most of the feed. Before the first run of this script the worst image
appeared in 16 posts and the mean was 6.5. Provisioning more assets does nothing on its own,
because the refs still point where they always did.

What it guarantees:

  - A post keeps its arity. An image post keeps one ref, a carousel keeps exactly as many as
    it had, a video post keeps one, a text post keeps none.
  - A video post gets a video and every other post gets an image. Banners are never used as
    post media: they are centre-cropped to 2:1 for profile headers and look wrong in a feed.
  - No asset is used more than MAX_USES times anywhere in the file.
  - An asset is drawn from the post's own topic wherever the topic still has capacity, and
    from the whole pool of the right kind only when it does not. Strict topic matching is not
    always satisfiable: demand per topic is uneven, and four topics carry video posts that
    VIDEO_TOPICS originally excluded. The run prints how many refs ended up off-topic.
  - A carousel never repeats an asset within itself.

Deterministic: assignment order is post id, then slot index, and ties between equally-used
assets break on manifest id. Running it twice on the same inputs produces the same file.

Usage:
    python scripts/remap-seed-media-refs.py            # rewrite posts.json
    python scripts/remap-seed-media-refs.py --check    # report only, write nothing
"""
import argparse
import collections
import io
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
POSTS_PATH = os.path.join(ROOT, "src", "main", "resources", "seed", "content", "posts.json")
MANIFEST_PATH = os.path.join(
    ROOT, "src", "main", "resources", "seed", "media", "media_manifest.json"
)

MAX_USES = 3


def load(path):
    text = io.open(path, encoding="utf-8", newline="").read()
    return json.loads(text), ("\r\n" if "\r\n" in text else "\n")


def save(path, doc, newline):
    out = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"
    if newline == "\r\n":
        out = out.replace("\n", "\r\n")
    io.open(path, "w", encoding="utf-8", newline="").write(out)


def pick(pool, uses, used_in_post):
    """Least-used asset in pool that is under the cap and not already in this post.

    Ties break on manifest id, so the choice does not depend on dict ordering."""
    best = None
    best_key = None
    for asset_id in pool:
        if asset_id in used_in_post:
            continue
        count = uses[asset_id]
        if count >= MAX_USES:
            continue
        key = (count, asset_id)
        if best_key is None or key < best_key:
            best, best_key = asset_id, key
    return best


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="report only, write nothing")
    args = parser.parse_args()

    posts_doc, posts_nl = load(POSTS_PATH)
    manifest, _ = load(MANIFEST_PATH)
    posts = posts_doc["posts"]

    images_by_topic = collections.defaultdict(list)
    videos_by_topic = collections.defaultdict(list)
    all_images, all_videos = [], []
    for entry in manifest["images"]:
        all_images.append(entry["id"])
        for topic in entry.get("topic_tags", []):
            images_by_topic[topic].append(entry["id"])
    for entry in manifest["videos"]:
        all_videos.append(entry["id"])
        for topic in entry.get("topic_tags", []):
            videos_by_topic[topic].append(entry["id"])

    before = collections.Counter()
    for post in posts:
        for ref in post.get("media_refs") or []:
            before[ref] += 1

    uses = collections.Counter()
    off_topic = 0
    assigned = 0
    failures = []

    for post in sorted(posts, key=lambda p: p["id"]):
        refs = post.get("media_refs") or []
        if not refs:
            continue
        topic = (post.get("topic_tags") or [None])[0]
        is_video = post["post_type"] == "video"
        topic_pool = (videos_by_topic if is_video else images_by_topic).get(topic, [])
        global_pool = all_videos if is_video else all_images

        new_refs = []
        used_in_post = set()
        for _ in refs:
            chosen = pick(topic_pool, uses, used_in_post)
            if chosen is None:
                chosen = pick(global_pool, uses, used_in_post)
                if chosen is not None:
                    off_topic += 1
            if chosen is None:
                failures.append(post["id"])
                break
            new_refs.append(chosen)
            used_in_post.add(chosen)
            uses[chosen] += 1
            assigned += 1
        if len(new_refs) == len(refs):
            post["media_refs"] = new_refs

    if failures:
        print("FAILED: no asset available for %d post(s): %s"
              % (len(failures), ", ".join(failures[:5])), file=sys.stderr)
        return 1

    distinct_before = len(before)
    print("slots assigned:        %d" % assigned)
    print("distinct assets used:  %d -> %d" % (distinct_before, len(uses)))
    print("mean uses per asset:   %.2f -> %.2f"
          % (sum(before.values()) / distinct_before, assigned / len(uses)))
    print("maximum uses:          %d -> %d" % (max(before.values()), max(uses.values())))
    print("off-topic refs:        %d of %d (%.1f%%)"
          % (off_topic, assigned, 100.0 * off_topic / assigned))

    if args.check:
        print("--check: posts.json not written")
        return 0

    save(POSTS_PATH, posts_doc, posts_nl)
    print("wrote %s" % POSTS_PATH)
    return 0


if __name__ == "__main__":
    sys.exit(main())
