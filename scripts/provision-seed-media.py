#!/usr/bin/env python3
"""Sources Pexels media for the seed dataset and uploads it to the R2 dev bucket.

Refuses to run against any R2 bucket other than the configured dev bucket name.
Idempotent: HEAD before PUT, skips assets already present.

Produces src/main/resources/seed/media/media_manifest.json with three top-level arrays:
images (120), videos (15), banners (25) - 160 objects total from a fresh provisioning run.
Avatars are sourced separately (a literal randomuser.me URL authored per user in users.json),
never uploaded to R2. (The checked-in manifest also carries 5 images reclassified from the
former avatar pool that real posts.json entries still reference - see media_manifest.json's
_reclassified_avatar_pool_note - which a fresh `provision` run does not reproduce; use
`--verify` against the existing manifest rather than re-running `provision` from scratch.)
Every physical asset is uploaded to R2 exactly once, under a stable
seed/library/{manifest_id}.{ext} key; downstream consumers (Task 5's
MediaSeedWriter) mint their own per-owner storage_key for each media_assets
row but reuse this manifest's single cdn_url, because storage_key is UNIQUE
in the database but the same physical file may back many owning rows.
"""
import argparse
import io
import json
import os
import sys
import time

import boto3
import requests
from PIL import Image

EXPECTED_BUCKET = "luvax-develop"
PEXELS_BASE = "https://api.pexels.com/v1"
PEXELS_VIDEO_BASE = "https://api.pexels.com/videos"
RATE_LIMIT_SLEEP_SECONDS = 35  # well under the 200/hour free-tier ceiling, and
# wide enough to stay clear of Pexels' short-window burst throttle: confirmed
# this session (coordinator-reproduced) that a 10-call burst at 1s spacing
# gets 401 (mislabeled, should be 429) on every call and the throttle then
# holds for 90+ seconds at rest; 3s-spaced calls saw a mix of 200/401. 35s
# between calls, with no immediate-retry-without-delay anywhere in this
# script, keeps every request well clear of that window.
MANIFEST_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..",
    "src",
    "main",
    "resources",
    "seed",
    "media",
    "media_manifest.json",
)

STORAGE_KEY_STRATEGY = (
    "Each of the 255 assets below is uploaded to R2 exactly once, under the "
    "stable key seed/library/{manifest_id}.{ext} recorded as this entry's "
    "storage_key, with cdn_url = {MEDIA_CDN_BASE_URL}/{storage_key}. "
    "media_assets.storage_key is UNIQUE, so when a downstream seeder (Task 5's "
    "MediaSeedWriter) creates a media_assets row for a given owner that reuses "
    "one of these manifest entries, it must mint its own fresh "
    "users/{ownerId}/media/{uuid}.{ext}-shaped storage_key (matching "
    "app.media.storage-key-pattern / MediaStorageKeyGenerator) to satisfy the "
    "constraint, but it writes back this manifest entry's single real cdn_url "
    "so every reader gets a working image without re-uploading the physical "
    "asset."
)

# Topic vocabulary is the 22 distinct topic_tags values used by posts.json
# (verified via posts.json content), cross-checked against hashtags.json and
# personas.json's persona.topics vocabulary. Task 2 adds ~188 more posts
# against this same vocabulary, so it does not need to change.
# Each entry: topic -> (pexels query, optional note explaining substitution).
IMAGE_TOPICS = [
    ("cost-of-living", "grocery shopping", "abstract economic concept; substituted concrete grocery/shopping imagery"),
    ("design", "graphic design workspace", None),
    ("fake-investment", "stock market chart", "scam/fraud concept has no direct photographic representation; substituted financial chart imagery commonly used in scam-warning content"),
    ("fnb", "restaurant food", None),
    ("freelance-work", "freelancer laptop cafe", "abstract labor arrangement; substituted concrete freelancer-at-work imagery"),
    ("gold-price", "gold bars", "abstract price/market concept; substituted concrete gold bullion imagery"),
    ("gym", "gym workout", None),
    ("job-market", "job interview", "abstract labor-market concept; substituted concrete interview imagery"),
    ("lifestyle", "lifestyle", None),
    ("motorbike", "motorcycle", None),
    ("online-shop", "online shopping packages", None),
    ("parenting", "parenting", None),
    ("personal-finance", "personal finance budgeting", None),
    ("photography", "photography camera", None),
    ("property-price", "real estate house", "abstract price/market concept; substituted concrete real-estate imagery"),
    ("rental", "apartment interior", None),
    ("running", "running", None),
    ("small-business", "small business owner", None),
    ("software-engineering", "programmer coding", None),
    ("studying-abroad", "university campus student", None),
    ("tech-general", "technology", None),
    ("travel-work", "digital nomad laptop", "abstract remote-work-while-travelling concept; substituted concrete digital-nomad imagery"),
    # Added with the --append expansion. These three exist because the verification
    # categories music, screen and gaming had no plausible subject among the original
    # accounts, so the seed gained one creator account per category and those accounts
    # need content of their own.
    ("music", "live music performance", None),
    ("film-screen", "film production set", None),
    ("gaming", "video game setup", None),
]

# Video-friendly subset of the same topic vocabulary (15 of the 22): excludes
# the abstract finance/career topics that have no natural motion footage.
VIDEO_TOPICS = [
    "software-engineering",
    "design",
    "photography",
    "fnb",
    "gym",
    "running",
    "motorbike",
    "travel-work",
    "small-business",
    "online-shop",
    "lifestyle",
    "rental",
    "tech-general",
    "studying-abroad",
    "job-market",
    "music",
    "film-screen",
    "gaming",
    # Carry video posts in posts.json. VIDEO_TOPICS originally excluded them as having no
    # natural motion footage, which left those posts borrowing a video from an unrelated
    # topic - the thing topic matching exists to avoid.
    "cost-of-living",
    "freelance-work",
    "parenting",
    "personal-finance",
]

# Banner sourcing: wide-aspect imagery for the creator/business account types
# in personas.json (p03_photographer_freelance=creator,
# p05_shop_owner_clothes=small_business, p07_fnb_owner=small_business). Only
# 15 users carry one of those three persona_ids in the current users.json,
# but the manifest provisions 25 banner objects (per section 3.3) as a pool
# headroom for Task 2's re-linking of posts.json/users.json.
BANNER_QUERIES = [
    ("photography studio wide", "photography", 9),
    ("boutique clothing shop wide", "small-business", 8),
    ("restaurant cafe interior wide", "fnb", 8),
]

# Pexels' orientation=landscape param only guarantees width > height, which is
# satisfied by an ordinary 3:2 (1.5:1) photo crop - not a banner-shaped image
# - and genuinely ultra-wide (>=1.8:1) originals turned out to be scarce to
# nonexistent for these specific topics (measured live: 1/9, 0/8, and 2/8
# across 4 result pages each). Rather than hunting for scarce wide originals,
# every selected landscape photo is deterministically center-cropped down to
# this fixed ratio before upload, which guarantees a genuinely banner-shaped
# result regardless of the source photo's native aspect ratio.
BANNER_TARGET_ASPECT_RATIO = 2.0


def r2_client():
    return boto3.client(
        "s3",
        endpoint_url=os.environ["R2_ENDPOINT"],
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        region_name=os.environ.get("R2_REGION", "auto"),
    )


def guard_bucket():
    bucket = os.environ["R2_BUCKET"]
    if bucket != EXPECTED_BUCKET:
        print(
            f"REFUSING: R2_BUCKET={bucket!r} is not the expected dev bucket {EXPECTED_BUCKET!r}",
            file=sys.stderr,
        )
        sys.exit(1)
    return bucket


def object_exists(s3, bucket, key):
    try:
        s3.head_object(Bucket=bucket, Key=key)
        return True
    except s3.exceptions.ClientError:
        return False


def pexels_headers():
    # Deliberately does NOT set a browser-spoofed User-Agent. Verified this
    # session (root-caused via matched curl/python-requests A-B probes against
    # Cloudflare, which fronts api.pexels.com per its response `server`
    # header): a request that claims to be Chrome via User-Agent while its
    # actual TLS/HTTP fingerprint is not Chrome gets a sustained 401
    # "Invalid API key" from Cloudflare's bot detection, while the honest
    # python-requests default User-Agent (no impersonation) passes reliably.
    # The earlier Chrome-UA "fix" in this file's history was itself the bug.
    return {"Authorization": os.environ["PEXELS_API_KEY"]}


def pexels_call_count():
    pexels_call_count.n = getattr(pexels_call_count, "n", 0) + 1
    return pexels_call_count.n


def pexels_get(url, params, max_attempts=25):
    """GETs a Pexels API endpoint, retrying transient 401/429/5xx responses with backoff.
    Root-caused this session: api.pexels.com's short-window burst throttle
    returns 401 "Invalid API key" instead of 429 when tripped (confirmed by
    reproducing it directly: a 10-call burst at 1s spacing got 401 on every
    call and the throttle then held for 90+ seconds at rest; 3s-spaced calls
    saw a mix of 200/401). It is not a credential problem, a UA/fingerprint
    block, or a query-specific block - x-ratelimit-remaining stayed near its
    25000 ceiling throughout every check. RATE_LIMIT_SLEEP_SECONDS (35s) and
    this retry's backoff both stay well clear of the window that trips it,
    and no code path here ever retries without a real delay first."""
    last_exc = None
    for attempt in range(1, max_attempts + 1):
        resp = requests.get(url, headers=pexels_headers(), params=params, timeout=30)
        if resp.status_code == 200:
            return resp
        print(
            f"  pexels non-200 on attempt {attempt}/{max_attempts}: "
            f"status={resp.status_code} body={resp.text[:300]!r}",
            file=sys.stderr,
        )
        last_exc = requests.exceptions.HTTPError(f"{resp.status_code} for {url} params={params}", response=resp)
        if resp.status_code not in (401, 429, 500, 502, 503, 504):
            raise last_exc
        if attempt < max_attempts:
            time.sleep(RATE_LIMIT_SLEEP_SECONDS)
    raise last_exc


def search_photos(query, per_page, page=1, orientation=None):
    """One GET /v1/search call (with transient-error retry). Sleeps after the call
    to respect the free-tier rate limit."""
    params = {"query": query, "per_page": per_page, "page": page}
    if orientation:
        params["orientation"] = orientation
    resp = pexels_get(f"{PEXELS_BASE}/search", params)
    call_no = pexels_call_count()
    print(f"[pexels call #{call_no}] GET /v1/search query={query!r} page={page} -> {resp.status_code}, {len(resp.json().get('photos', []))} photos")
    time.sleep(RATE_LIMIT_SLEEP_SECONDS)
    return resp.json().get("photos", [])


def search_videos(query, per_page, page=1):
    """One GET /videos/search call (with transient-error retry). Sleeps after the
    call to respect the free-tier rate limit."""
    params = {"query": query, "per_page": per_page, "page": page}
    resp = pexels_get(f"{PEXELS_VIDEO_BASE}/search", params)
    call_no = pexels_call_count()
    videos = resp.json().get("videos", [])
    print(f"[pexels call #{call_no}] GET /videos/search query={query!r} page={page} -> {resp.status_code}, {len(videos)} videos")
    time.sleep(RATE_LIMIT_SLEEP_SECONDS)
    return videos


def pick_video_file(video):
    """Selects an mp4 file under 60s duration, preferring a file near 1280px wide."""
    if video.get("duration", 999) >= 60:
        return None
    mp4_files = [f for f in video.get("video_files", []) if f.get("file_type") == "video/mp4" and f.get("width")]
    if not mp4_files:
        return None
    return min(mp4_files, key=lambda f: abs(f["width"] - 1280))


def download_bytes(url):
    resp = requests.get(url, timeout=60)
    resp.raise_for_status()
    return resp.content


def upload_asset(s3, bucket, manifest_id, ext, download_url, content_type, post_process=None, read_back_on_skip=False):
    """HEAD-before-PUT idempotent upload.

    Checks R2 first and returns immediately on a hit - the source asset is
    never downloaded from Pexels' CDN when the object already exists, which
    is the idempotency-of-download guarantee this script advertises.

    `post_process`, if given, is applied to the downloaded bytes (e.g. to
    center-crop an image to a fixed aspect ratio) before they are uploaded -
    the uploaded object and the returned data are always the post-processed
    bytes, never the raw download.

    `read_back_on_skip`, if True, fetches the already-uploaded object's real
    bytes from R2 (not from Pexels - the CDN download is still skipped) when
    the object already exists, so a caller that needs the real stored
    dimensions of a skipped asset (e.g. a resized or cropped image, where
    Pexels' own API-reported dimensions describe a different variant than
    what was actually uploaded) can decode them accurately instead of
    guessing from unrelated API metadata. Left False for videos, whose
    accurate dimensions are already known from the selected video file's own
    API metadata without needing to fetch anything.

    Returns (storage_key, cdn_url, file_size_bytes, uploaded_now, data).
    `data` is the (post-processed) bytes when `uploaded_now` is True, the
    real stored bytes (read back from R2) when skipped with
    `read_back_on_skip=True`, or None when skipped without it.
    """
    storage_key = f"seed/library/{manifest_id}.{ext}"
    cdn_base = os.environ["MEDIA_CDN_BASE_URL"].rstrip("/")
    cdn_url = f"{cdn_base}/{storage_key}"
    if object_exists(s3, bucket, storage_key):
        head = s3.head_object(Bucket=bucket, Key=storage_key)
        data = None
        if read_back_on_skip:
            data = s3.get_object(Bucket=bucket, Key=storage_key)["Body"].read()
        return storage_key, cdn_url, head["ContentLength"], False, data
    data = download_bytes(download_url)
    if post_process:
        data = post_process(data)
    s3.put_object(Bucket=bucket, Key=storage_key, Body=data, ContentType=content_type)
    return storage_key, cdn_url, len(data), True, data


def crop_box_for_ratio(width, height, target_ratio):
    """Computes a centered crop box of (width, height) down to target_ratio
    (width:height), never upscaling. Returns (left, top, right, bottom,
    crop_width, crop_height)."""
    current_ratio = width / height
    if current_ratio > target_ratio:
        crop_h, crop_w = height, round(height * target_ratio)
    else:
        crop_w, crop_h = width, round(width / target_ratio)
    left = (width - crop_w) // 2
    top = (height - crop_h) // 2
    return left, top, left + crop_w, top + crop_h, crop_w, crop_h


def crop_bytes_to_ratio(data, target_ratio):
    """Center-crops image bytes down to target_ratio and returns re-encoded JPEG bytes."""
    with Image.open(io.BytesIO(data)) as im:
        im = im.convert("RGB")
        left, top, right, bottom, _, _ = crop_box_for_ratio(im.width, im.height, target_ratio)
        cropped = im.crop((left, top, right, bottom))
        out = io.BytesIO()
        cropped.save(out, format="JPEG", quality=90)
        return out.getvalue()


def _fallback_dimensions(photo, crop_to_ratio):
    """Last-resort dimension estimate when the real stored bytes could not be
    read back and decoded at all (e.g. a decode failure) - approximates from
    Pexels' API-reported original dimensions rather than leaving the field
    empty. Not expected to be hit in normal operation."""
    width, height = photo.get("width"), photo.get("height")
    if crop_to_ratio and width and height:
        _, _, _, _, width, height = crop_box_for_ratio(width, height, crop_to_ratio)
    return width, height


def image_entry(manifest_id, role, topic_tags, photo, s3, bucket, crop_to_ratio=None):
    # Pexels' photo["width"]/photo["height"] describe the original master
    # image, not the src["large"] variant this script actually downloads and
    # stores (and, for banners, not the further center-cropped result
    # either) - recording them directly was a real bug (confirmed live: a
    # ~7x mismatch between recorded and actual stored pixel dimensions).
    # read_back_on_skip=True makes upload_asset fetch the real stored bytes
    # from R2 even when the CDN download is skipped, so the block below
    # always decodes real dimensions from the actual asset - freshly
    # downloaded, freshly cropped, or read back unchanged from a prior run -
    # rather than ever trusting Pexels' unrelated master-image metadata.
    post_process = (lambda data: crop_bytes_to_ratio(data, crop_to_ratio)) if crop_to_ratio else None
    storage_key, cdn_url, file_size, uploaded_now, data = upload_asset(
        s3, bucket, manifest_id, "jpg", photo["src"]["large"], "image/jpeg",
        post_process=post_process, read_back_on_skip=True,
    )
    if data is not None:
        try:
            with Image.open(io.BytesIO(data)) as im:
                width, height = im.size
        except Exception:
            width, height = _fallback_dimensions(photo, crop_to_ratio)
    else:
        # Only reachable if read_back_on_skip somehow failed to return bytes
        # for an object object_exists just confirmed - not expected, but
        # kept as a last-resort fallback rather than raising.
        width, height = _fallback_dimensions(photo, crop_to_ratio)
    return {
        "id": manifest_id,
        "kind": "image",
        "role": role,
        "topic_tags": topic_tags,
        "pexels_id": photo["id"],
        "pexels_url": photo["url"],
        "photographer": photo["photographer"],
        "license": "Pexels License (free for commercial and personal use, no attribution required, https://www.pexels.com/license/)",
        "width": width,
        "height": height,
        "mime_type": "image/jpeg",
        "file_size_bytes": file_size,
        "storage_key": storage_key,
        "cdn_url": cdn_url,
    }, uploaded_now


def video_entry(manifest_id, role, topic_tags, video, file, s3, bucket):
    storage_key, cdn_url, file_size, uploaded_now, _data = upload_asset(
        s3, bucket, manifest_id, "mp4", file["link"], "video/mp4"
    )
    return {
        "id": manifest_id,
        "kind": "video",
        "role": role,
        "topic_tags": topic_tags,
        "pexels_id": video["id"],
        "pexels_url": video["url"],
        "photographer": video.get("user", {}).get("name", "unknown"),
        "license": "Pexels License (free for commercial and personal use, no attribution required, https://www.pexels.com/license/)",
        "width": file["width"],
        "height": file["height"],
        "duration_seconds": video["duration"],
        "mime_type": "video/mp4",
        "file_size_bytes": file_size,
        "storage_key": storage_key,
        "cdn_url": cdn_url,
    }, uploaded_now


def image_quota():
    """Splits image slots across IMAGE_TOPICS (base 5 each + 1 more for the first 10),
    then marks every 8th slot as role=story and the rest role=post.

    The total is derived from the topic list rather than asserted against a constant,
    because --append added three topics and a hardcoded 120 would have made a fresh
    provision run fail rather than simply produce a larger quota."""
    base, extra = 5, 10
    flat = []
    for idx, (topic, query, note) in enumerate(IMAGE_TOPICS):
        count = base + (1 if idx < extra else 0)
        for _ in range(count):
            flat.append((topic, query))
    assert len(flat) == base * len(IMAGE_TOPICS) + extra, len(flat)
    result = []
    for i, (topic, query) in enumerate(flat):
        role = "story" if i % 8 == 0 else "post"
        result.append((topic, query, role))
    story_count = sum(1 for _, _, r in result if r == "story")
    post_count = sum(1 for _, _, r in result if r == "post")
    print(f"image quota built: {post_count} post + {story_count} story = {len(result)}")
    return result


def video_quota():
    """One video per topic in VIDEO_TOPICS, first 5 tagged role=story, rest role=post."""
    result = []
    for i, topic in enumerate(VIDEO_TOPICS):
        role = "story" if i < 5 else "post"
        query = next(q for t, q, _ in IMAGE_TOPICS if t == topic)
        result.append((topic, query, role))
    return result


def build_images(s3, bucket):
    quota = image_quota()
    seen_ids = set()
    entries = []
    uploaded = 0
    by_topic_query = {}
    for topic, query, role in quota:
        by_topic_query.setdefault((topic, query), []).append(role)
    seq = 0
    for (topic, query), roles in by_topic_query.items():
        needed = len(roles)
        candidates = search_photos(query, per_page=20)
        role_iter = iter(roles)
        picked = 0
        for photo in candidates:
            if picked >= needed:
                break
            if photo["id"] in seen_ids:
                continue
            seen_ids.add(photo["id"])
            seq += 1
            role = next(role_iter)
            manifest_id = f"pexels_img_{seq:03d}"
            entry, was_uploaded = image_entry(manifest_id, role, [topic], photo, s3, bucket)
            entries.append(entry)
            uploaded += 1 if was_uploaded else 0
            picked += 1
        if picked < needed:
            print(f"WARNING: topic {topic!r} query {query!r} only yielded {picked}/{needed} unique photos", file=sys.stderr)
    return entries, uploaded


def build_videos(s3, bucket):
    quota = video_quota()
    seen_ids = set()
    entries = []
    uploaded = 0
    seq = 0
    for topic, query, role in quota:
        candidates = search_videos(query, per_page=15)
        picked_file = None
        picked_video = None
        for video in candidates:
            if video["id"] in seen_ids:
                continue
            f = pick_video_file(video)
            if f is None:
                continue
            picked_video, picked_file = video, f
            break
        if picked_video is None:
            print(f"WARNING: video topic {topic!r} query {query!r} yielded no usable <60s mp4 result", file=sys.stderr)
            continue
        seen_ids.add(picked_video["id"])
        seq += 1
        manifest_id = f"pexels_vid_{seq:03d}"
        entry, was_uploaded = video_entry(manifest_id, role, [topic], picked_video, picked_file, s3, bucket)
        entries.append(entry)
        uploaded += 1 if was_uploaded else 0
    return entries, uploaded


def build_banners(s3, bucket):
    """Selects landscape-oriented photos for banners and center-crops every
    one of them down to BANNER_TARGET_ASPECT_RATIO (2:1) before upload, so
    every banner is genuinely wide regardless of the source photo's native
    aspect ratio - genuinely ultra-wide originals turned out to be scarce to
    nonexistent for these specific topics (measured live), so selection no
    longer depends on finding them."""
    entries = []
    uploaded = 0
    seen_ids = set()
    seq = 0
    for query, topic, needed in BANNER_QUERIES:
        candidates = search_photos(query, per_page=25, orientation="landscape")
        picked = 0
        for photo in candidates:
            if picked >= needed:
                break
            if photo["id"] in seen_ids:
                continue
            width, height = photo.get("width"), photo.get("height")
            if not width or not height or width < height:
                # Guards against a stray non-landscape result slipping
                # through the orientation filter; cropping a portrait image
                # down to a wide banner would discard most of the frame.
                continue
            seen_ids.add(photo["id"])
            seq += 1
            manifest_id = f"pexels_banner_{seq:03d}"
            entry, was_uploaded = image_entry(
                manifest_id, "banner", [topic], photo, s3, bucket, crop_to_ratio=BANNER_TARGET_ASPECT_RATIO
            )
            entries.append(entry)
            uploaded += 1 if was_uploaded else 0
            picked += 1
        if picked < needed:
            print(f"WARNING: banner query {query!r} only yielded {picked}/{needed} unique landscape photos", file=sys.stderr)
    return entries, uploaded


def build_manifest(s3, bucket):
    images, up1 = build_images(s3, bucket)
    videos, up2 = build_videos(s3, bucket)
    banners, up4 = build_banners(s3, bucket)
    total_uploaded = up1 + up2 + up4

    topic_notes = {t: note for t, _, note in IMAGE_TOPICS if note}
    topic_notes["_banner_pool_note"] = (
        "Only 15 users in the current users.json carry a creator/small_business "
        "persona_id (p03_photographer_freelance=6, p05_shop_owner_clothes=5, "
        "p07_fnb_owner=4). 25 banner objects are provisioned per section 3.3 as "
        "pool headroom for Task 2's re-linking of posts.json/users.json."
    )

    manifest = {
        "_storage_key_strategy": STORAGE_KEY_STRATEGY,
        "_topic_notes": topic_notes,
        "images": images,
        "videos": videos,
        "banners": banners,
    }
    print(
        f"manifest built: images={len(images)} videos={len(videos)} "
        f"banners={len(banners)} "
        f"total={len(images) + len(videos) + len(banners)}, "
        f"newly uploaded this run={total_uploaded}, "
        f"pexels api calls this run={getattr(pexels_call_count, 'n', 0)}"
    )
    return manifest


def provision(s3, bucket):
    manifest = build_manifest(s3, bucket)
    with open(MANIFEST_PATH, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {MANIFEST_PATH}")


APPEND_PER_PAGE = 80
APPEND_MAX_PAGES = 4


def load_manifest():
    with open(MANIFEST_PATH, encoding="utf-8") as f:
        return json.load(f)


def write_manifest(manifest):
    with open(MANIFEST_PATH, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")


def used_pexels_ids(manifest):
    """Every Pexels id the manifest already references, across all three pools.

    Append selection skips these, so a re-run never picks a photo the manifest
    already holds and never mints a second manifest id for one source asset."""
    ids = set()
    for pool in ("images", "videos", "banners"):
        for entry in manifest.get(pool, []):
            if entry.get("pexels_id") is not None:
                ids.add(entry["pexels_id"])
    return ids


def next_sequence(entries, prefix):
    highest = 0
    for entry in entries:
        ident = entry.get("id", "")
        if ident.startswith(prefix):
            suffix = ident[len(prefix):]
            if suffix.isdigit():
                highest = max(highest, int(suffix))
    return highest + 1


def spread(total, buckets):
    """Splits total across buckets as evenly as possible, remainder to the front."""
    base, remainder = divmod(total, buckets)
    return [base + (1 if i < remainder else 0) for i in range(buckets)]


def append_images(s3, bucket, manifest, count, seen_ids, checkpoint, per_topic=None):
    seq = next_sequence(manifest["images"], "pexels_img_")
    if per_topic:
        quota = [per_topic.get(topic, 0) for topic, _q, _n in IMAGE_TOPICS]
    else:
        quota = spread(count, len(IMAGE_TOPICS))
    added = 0
    uploaded = 0
    for (topic, query, _note), needed in zip(IMAGE_TOPICS, quota):
        if needed == 0:
            continue
        got = 0
        page = 1
        while got < needed and page <= APPEND_MAX_PAGES:
            candidates = search_photos(query, per_page=APPEND_PER_PAGE, page=page)
            if not candidates:
                break
            for photo in candidates:
                if got >= needed:
                    break
                if photo["id"] in seen_ids:
                    continue
                seen_ids.add(photo["id"])
                # Same 1-in-8 story ratio the original quota uses, counted across the
                # whole appended run rather than restarting per topic.
                role = "story" if added % 8 == 0 else "post"
                entry, was_uploaded = image_entry(
                    "pexels_img_%03d" % seq, role, [topic], photo, s3, bucket
                )
                manifest["images"].append(entry)
                uploaded += 1 if was_uploaded else 0
                seq += 1
                got += 1
                added += 1
            page += 1
        if got < needed:
            print(
                "WARNING: image topic %r yielded %d/%d unused photos" % (topic, got, needed),
                file=sys.stderr,
            )
        checkpoint()
    return added, uploaded


def append_videos(s3, bucket, manifest, count, seen_ids, checkpoint, per_topic=None):
    seq = next_sequence(manifest["videos"], "pexels_vid_")
    if per_topic:
        quota = [per_topic.get(topic, 0) for topic in VIDEO_TOPICS]
    else:
        quota = spread(count, len(VIDEO_TOPICS))
    added = 0
    uploaded = 0
    for topic, needed in zip(VIDEO_TOPICS, quota):
        if needed == 0:
            continue
        query = next(q for t, q, _ in IMAGE_TOPICS if t == topic)
        got = 0
        page = 1
        while got < needed and page <= APPEND_MAX_PAGES:
            candidates = search_videos(query, per_page=APPEND_PER_PAGE, page=page)
            if not candidates:
                break
            for video in candidates:
                if got >= needed:
                    break
                if video["id"] in seen_ids:
                    continue
                picked = pick_video_file(video)
                if picked is None:
                    continue
                seen_ids.add(video["id"])
                role = "story" if added % 5 == 0 else "post"
                entry, was_uploaded = video_entry(
                    "pexels_vid_%03d" % seq, role, [topic], video, picked, s3, bucket
                )
                manifest["videos"].append(entry)
                uploaded += 1 if was_uploaded else 0
                seq += 1
                got += 1
                added += 1
            page += 1
        if got < needed:
            print(
                "WARNING: video topic %r yielded %d/%d usable results" % (topic, got, needed),
                file=sys.stderr,
            )
        checkpoint()
    return added, uploaded


def append(s3, bucket, image_count, video_count, image_topics=None, video_topics=None):
    """Grows the existing manifest without disturbing a single entry already in it.

    `provision` renumbers from pexels_img_001 every time it runs, so re-running it
    would rewrite every manifest id and break every media_refs reference in
    content/posts.json. This mode continues the numbering instead.

    The manifest is rewritten after every topic rather than once at the end, so a run
    interrupted partway keeps what it already uploaded. Re-running then resumes:
    already-referenced Pexels ids are skipped by selection, and upload_asset HEADs
    before it PUTs, so nothing is fetched or stored twice."""
    manifest = load_manifest()
    before_images = len(manifest["images"])
    before_videos = len(manifest["videos"])
    seen_ids = used_pexels_ids(manifest)
    print(
        "append starting: %d images, %d videos, %d pexels ids already referenced"
        % (before_images, before_videos, len(seen_ids))
    )

    def checkpoint():
        write_manifest(manifest)

    added_images = uploaded_images = 0
    added_videos = uploaded_videos = 0
    try:
        if image_count or image_topics:
            added_images, uploaded_images = append_images(
                s3, bucket, manifest, image_count, seen_ids, checkpoint, image_topics
            )
        if video_count or video_topics:
            added_videos, uploaded_videos = append_videos(
                s3, bucket, manifest, video_count, seen_ids, checkpoint, video_topics
            )
    finally:
        write_manifest(manifest)

    print(
        "append complete: +%d images (now %d), +%d videos (now %d), "
        "newly uploaded this run=%d, pexels api calls this run=%d"
        % (
            added_images,
            len(manifest["images"]),
            added_videos,
            len(manifest["videos"]),
            uploaded_images + uploaded_videos,
            getattr(pexels_call_count, "n", 0),
        )
    )
    print("wrote %s" % MANIFEST_PATH)


def verify_manifest(s3, bucket):
    """For every manifest entry, HEAD the real R2 key and compare ContentLength/ContentType
    against the manifest's recorded file_size_bytes/mime_type. Prints mismatches only;
    empty output means clean. Does not upload anything."""
    if not os.path.exists(MANIFEST_PATH):
        print(f"REFUSING: manifest not found at {MANIFEST_PATH}", file=sys.stderr)
        sys.exit(1)
    with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    mismatches = 0
    checked = 0
    for array_name in ("images", "videos", "banners"):
        for entry in manifest.get(array_name, []):
            checked += 1
            key = entry["storage_key"]
            try:
                head = s3.head_object(Bucket=bucket, Key=key)
            except s3.exceptions.ClientError as exc:
                print(f"MISMATCH [{array_name}/{entry['id']}]: object missing at key={key}: {exc}")
                mismatches += 1
                continue
            actual_size = head["ContentLength"]
            actual_type = head["ContentType"]
            if actual_size != entry["file_size_bytes"]:
                print(
                    f"MISMATCH [{array_name}/{entry['id']}]: file_size_bytes manifest={entry['file_size_bytes']} r2={actual_size}"
                )
                mismatches += 1
            if actual_type != entry["mime_type"]:
                print(
                    f"MISMATCH [{array_name}/{entry['id']}]: mime_type manifest={entry['mime_type']} r2={actual_type}"
                )
                mismatches += 1
    print(f"verify complete: {checked} entries checked, {mismatches} mismatches")
    if mismatches:
        sys.exit(1)


def parse_topic_counts(parser, raw_values, known_topics):
    """Parses repeated TOPIC=N arguments into {topic: count}, refusing an unknown topic."""
    counts = {}
    for raw in raw_values:
        topic, sep, value = raw.partition("=")
        if not sep or not value.isdigit():
            parser.error("expected TOPIC=N, got %r" % raw)
        if topic not in known_topics:
            parser.error("unknown topic %r; known topics: %s"
                         % (topic, ", ".join(sorted(known_topics))))
        counts[topic] = counts.get(topic, 0) + int(value)
    return counts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify", action="store_true")
    parser.add_argument(
        "--append",
        action="store_true",
        help="grow the existing manifest instead of rebuilding it from scratch",
    )
    parser.add_argument("--images", type=int, default=0, help="images to append")
    parser.add_argument("--videos", type=int, default=0, help="videos to append")
    parser.add_argument(
        "--image-topic",
        action="append",
        default=[],
        metavar="TOPIC=N",
        help="append N images for one topic; repeatable, and overrides --images",
    )
    parser.add_argument(
        "--video-topic",
        action="append",
        default=[],
        metavar="TOPIC=N",
        help="append N videos for one topic; repeatable, and overrides --videos",
    )
    args = parser.parse_args()
    bucket = guard_bucket()
    s3 = r2_client()
    if args.verify:
        verify_manifest(s3, bucket)
        return
    if args.append:
        image_topics = parse_topic_counts(parser, args.image_topic,
                                          [t for t, _q, _n in IMAGE_TOPICS])
        video_topics = parse_topic_counts(parser, args.video_topic, VIDEO_TOPICS)
        if args.images <= 0 and args.videos <= 0 and not image_topics and not video_topics:
            parser.error(
                "--append needs --images/--videos or --image-topic/--video-topic"
            )
        append(s3, bucket, args.images, args.videos, image_topics, video_topics)
        return
    provision(s3, bucket)


if __name__ == "__main__":
    main()
