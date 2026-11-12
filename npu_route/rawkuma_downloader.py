"""Chapter downloader: Rawkuma-style WordPress readers and NHentai galleries.

NHentai links look like https://nhentai.net/g/{id}/ (whole gallery) or
https://nhentai.net/g/{id}/{page}/ (gallery from that page onward)."""
from __future__ import annotations

import json
import os
import queue
import re
import threading
import time
import tkinter as tk
from html import unescape
from html.parser import HTMLParser
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen

GLOBAL_CONFIG_PATH = Path.home() / ".tachiyomi" / "downloader_settings.json"
LOCAL_CONFIG_PATH = Path(__file__).resolve().parent / ".settings.json"
HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"}


def find_repo_root(start: Path) -> Path | None:
    curr = start.resolve()
    for parent in [curr] + list(curr.parents):
        if (parent / ".git").exists() or (parent / "settings.gradle.kts").exists():
            return parent
    return None


def get_adaptive_save_root() -> Path:
    """Finds or adapts to the most appropriate saved directory."""
    # 1. Global user config
    if GLOBAL_CONFIG_PATH.exists():
        try:
            cfg = json.loads(GLOBAL_CONFIG_PATH.read_text(encoding="utf-8"))
            if cfg.get("dest") and Path(cfg["dest"]).exists():
                return Path(cfg["dest"])
        except Exception:
            pass

    # 2. Local config
    if LOCAL_CONFIG_PATH.exists():
        try:
            cfg = json.loads(LOCAL_CONFIG_PATH.read_text(encoding="utf-8"))
            if cfg.get("dest") and Path(cfg["dest"]).exists():
                return Path(cfg["dest"])
        except Exception:
            pass

    # 3. Environment variables
    for env_var in ("MANGA_DOWNLOAD_DIR", "RAWKUMA_DOWNLOAD_DIR", "TACHIYOMI_DOWNLOAD_DIR"):
        val = os.environ.get(env_var)
        if val and Path(val).exists():
            return Path(val)

    # 4. Check repo folders if present
    repo_root = find_repo_root(Path(__file__))
    if repo_root:
        calib_route = repo_root / "npu_route" / "calibration" / "rawkuma"
        if calib_route.exists():
            return calib_route
        calib_saved = repo_root / "saved_models" / "calibration_dataset" / "rawkuma"
        if calib_saved.exists():
            return calib_saved

    # 5. User Downloads folder
    downloads = Path.home() / "Downloads"
    if downloads.exists():
        manga_test = downloads / "manga_test_chapters"
        return manga_test

    return Path.home() / "Downloads" / "manga_test_chapters"


def save_settings(dest: Path | str) -> None:
    dest_str = str(Path(dest))
    try:
        GLOBAL_CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        GLOBAL_CONFIG_PATH.write_text(json.dumps({"dest": dest_str}, indent=2), encoding="utf-8")
    except Exception:
        pass
    try:
        LOCAL_CONFIG_PATH.write_text(json.dumps({"dest": dest_str}, indent=2), encoding="utf-8")
    except Exception:
        pass


class ChapterImages(HTMLParser):
    def __init__(self):
        super().__init__()
        self.depth = 0
        self.urls: list[str] = []

    def handle_starttag(self, tag, attrs):
        attr_map = dict(attrs)
        if tag == "section" and "data-image-data" in attr_map:
            self.depth = 1
        elif self.depth and tag == "section":
            self.depth += 1
        elif (self.depth or (tag == "div" and attr_map.get("id") in ("reading-content", "readerarea"))) and tag == "img":
            source = attr_map.get("data-src") or attr_map.get("data-lazy-src") or attr_map.get("data-original") or attr_map.get("src")
            if source:
                self.urls.append(source)
        elif tag == "img" and any(k in attr_map for k in ("data-src", "data-lazy-src", "data-original")):
            source = attr_map.get("data-src") or attr_map.get("data-lazy-src") or attr_map.get("data-original")
            if source and not any(ch in source for ch in ("logo", "avatar", "icon", "banner", "favicon")):
                self.urls.append(source)

    def handle_endtag(self, tag):
        if self.depth and tag == "section":
            self.depth -= 1


def chapter_location(url: str, base_root: Path | None = None) -> Path:
    if base_root is None:
        base_root = get_adaptive_save_root()
    clean_url = url.strip()
    if clean_url and not urlparse(clean_url).scheme:
        clean_url = "https://" + clean_url
    parsed = urlparse(clean_url)
    if not parsed.netloc:
        raise ValueError("Invalid URL: must have a valid hostname.")
    parts = [part for part in parsed.path.split("/") if part]
    if not parts:
        raise ValueError("Expected a chapter link like: /manga/title/chapter-number/.")

    if "manga" in parts:
        idx = parts.index("manga")
        slug = parts[idx + 1] if len(parts) > idx + 1 else "manga"
        chapter = parts[-1] if len(parts) > idx + 2 else "chapter"
    elif len(parts) >= 2:
        slug = parts[0]
        chapter = parts[-1]
    else:
        slug = "manga"
        chapter = parts[0]

    slug = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", slug).strip(" .")
    chapter = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", chapter).strip(" .")
    return base_root / (slug or "manga") / (chapter or "chapter")


def request(url: str, referer: str | None = None):
    headers = dict(HEADERS)
    if referer:
        headers["Referer"] = referer
    return urlopen(Request(url, headers=headers), timeout=30)


NHENTAI_GALLERY_RE = re.compile(r"/g/(\d+)(?:/(\d+))?/?$")
NHENTAI_IMAGE_RE = re.compile(
    r"https?://[a-z0-9.]*nhentai\.net/galleries/\d+/\d+\.(?:jpe?g|png|gif|webp)", re.I
)
NHENTAI_THUMB_RE = re.compile(
    r"https?://[a-z0-9.]*nhentai\.net/galleries/(\d+)/(\d+)t?\.(jpe?g|png|gif|webp)", re.I
)
NHENTAI_TITLE_RE = re.compile(r"<h1[^>]*>(.*?)</h1>", re.S)
NHENTAI_EXT_BY_CODE = {"j": ".jpg", "p": ".png", "g": ".gif", "w": ".webp"}
NHENTAI_DELAY_SECONDS = 0.5
NHENTAI_MAX_DELAY_SECONDS = 8.0
_nhentai_backoff_seconds = NHENTAI_DELAY_SECONDS
_nhentai_last_request: float | None = None


def _nhentai_delay_seconds() -> float:
    return min(_nhentai_backoff_seconds, NHENTAI_MAX_DELAY_SECONDS)


def nhentai_open(url: str, referer: str | None = None):
    """Paced GET that backs off on 429/503 instead of failing the gallery."""
    global _nhentai_last_request, _nhentai_backoff_seconds
    attempt = 0
    while True:
        if _nhentai_last_request is not None:
            wait = _nhentai_delay_seconds() - (time.monotonic() - _nhentai_last_request)
            if wait > 0:
                time.sleep(wait)
        try:
            response = request(url, referer=referer)
        except HTTPError as error:
            _nhentai_last_request = time.monotonic()
            if error.code in (429, 503) and attempt < 3:
                _nhentai_backoff_seconds = min(_nhentai_backoff_seconds * 2, NHENTAI_MAX_DELAY_SECONDS)
                retry_after = error.headers.get("Retry-After") if error.headers else None
                delay = _nhentai_delay_seconds()
                if retry_after and retry_after.replace(".", "", 1).isdigit():
                    delay = min(float(retry_after), 10.0)
                time.sleep(delay)
                attempt += 1
                continue
            raise
        _nhentai_last_request = time.monotonic()
        _nhentai_backoff_seconds = NHENTAI_DELAY_SECONDS
        return response


def normalize_link(url: str) -> str:
    clean = url.strip()
    if clean and not urlparse(clean).scheme:
        clean = "https://" + clean
    return clean


def is_nhentai_link(url: str) -> bool:
    host = (urlparse(normalize_link(url)).netloc or "").lower()
    return host == "nhentai.net" or host.endswith(".nhentai.net")


def parse_nhentai_url(url: str) -> tuple[int, int | None]:
    """Returns (gallery_id, start_page); start_page is None for /g/{id}/ links."""
    path = urlparse(normalize_link(url)).path
    match = NHENTAI_GALLERY_RE.search(path)
    if not match:
        raise ValueError(
            "Expected an nhentai gallery link like https://nhentai.net/g/123456/ "
            "(optionally /123456/2/ to start from page 2)."
        )
    return int(match.group(1)), int(match.group(2)) if match.group(2) else None


def sanitize_name(text: str, limit: int = 60) -> str:
    safe = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", text).strip(" ._")
    return safe[:limit].strip(" ._")


def parse_gallery_payload(raw: str) -> dict:
    """Parses the /api/gallery/{id} JSON into title + (number, url, suffix) pages."""
    data = json.loads(raw)
    media = str(data["media_id"])
    entries = data.get("images", {}).get("pages") or []
    pages = []
    for number, page in enumerate(entries, 1):
        suffix = NHENTAI_EXT_BY_CODE.get(str(page.get("t", "j")).lower(), ".jpg")
        pages.append((number, f"https://i.nhentai.net/galleries/{media}/{number}{suffix}", suffix))
    if not pages:
        raise ValueError("Gallery API returned no pages.")
    titles = data.get("title") or {}
    title = titles.get("english") or titles.get("japanese") or titles.get("pretty") or ""
    return {"title": title, "pages": pages, "num_pages": int(data.get("num_pages") or len(pages))}


def _nhentai_page_title(decoded: str) -> str:
    match = NHENTAI_TITLE_RE.search(decoded)
    if not match:
        return ""
    return " ".join(unescape(re.sub(r"<[^>]+>", "", match.group(1))).split())


def nhentai_gallery_api(gallery_id: int) -> dict:
    with nhentai_open(f"https://nhentai.net/api/gallery/{gallery_id}") as response:
        raw = response.read(4 * 1024 * 1024 + 1).decode("utf-8", errors="replace")
    return parse_gallery_payload(raw)


def parse_gallery_page_html(decoded: str) -> dict:
    """Extracts media_id + per-page extensions from the gallery landing page."""
    media_votes: dict[str, int] = {}
    pages_by_media: dict[str, dict[int, str]] = {}
    for media, number, ext in NHENTAI_THUMB_RE.findall(decoded):
        media_votes[media] = media_votes.get(media, 0) + 1
        pages_by_media.setdefault(media, {})[int(number)] = "." + ext.lower()
    if not media_votes:
        raise ValueError("Gallery page contains no page thumbnails.")
    media = max(media_votes, key=media_votes.get)
    exts = pages_by_media[media]
    numbers = sorted(exts)
    if not numbers or numbers[0] != 1 or numbers != list(range(1, numbers[-1] + 1)):
        raise ValueError("Gallery thumbnails are incomplete or non-contiguous.")
    pages = [
        (number, f"https://i.nhentai.net/galleries/{media}/{number}{exts[number]}", exts[number])
        for number in numbers
    ]
    return {"title": _nhentai_page_title(decoded), "pages": pages, "num_pages": len(pages)}


def nhentai_gallery_page(gallery_id: int) -> dict:
    """One-request discovery: the gallery landing page lists every page thumbnail."""
    with nhentai_open(f"https://nhentai.net/g/{gallery_id}/") as response:
        decoded = response.read(12 * 1024 * 1024 + 1).decode("utf-8", errors="replace")
    return parse_gallery_page_html(decoded)


def nhentai_reader_page(gallery_id: int, number: int) -> tuple[str | None, str]:
    """Scrapes /g/{id}/{n}/ for its full-size image URL and the gallery title."""
    page_url = f"https://nhentai.net/g/{gallery_id}/{number}/"
    with nhentai_open(page_url) as response:
        decoded = response.read(12 * 1024 * 1024 + 1).decode("utf-8", errors="replace")
    match = NHENTAI_IMAGE_RE.search(decoded)
    if not match:
        return None, ""
    return match.group(0), _nhentai_page_title(decoded)


def nhentai_target_dir(gallery_id: int, title: str, base_root: Path) -> Path:
    safe = sanitize_name(title)
    name = f"{gallery_id} - {safe}" if safe else str(gallery_id)
    return base_root / "nhentai" / name


def migrate_legacy_gallery_folder(target: Path, gallery_id: int, base_root: Path) -> None:
    """Adopts a bare <id>/ folder left by the old title-less reader path."""
    legacy = base_root / "nhentai" / str(gallery_id)
    if not target.exists() and legacy.is_dir():
        try:
            legacy.rename(target)
        except OSError:
            pass


def chapter_pages(url: str) -> list[str]:
    with request(url) as response:
        content = response.read(12 * 1024 * 1024 + 1)
    if len(content) > 12 * 1024 * 1024:
        raise ValueError("Chapter page is unexpectedly large.")
    decoded = content.decode("utf-8", errors="replace")
    parser = ChapterImages()
    parser.feed(decoded)
    urls = list(dict.fromkeys(urljoin(url, item) for item in parser.urls))

    if not urls:
        # Check WordPress ts_reader.run({...}) style readers
        m = re.search(r"ts_reader\.run\((\{.*?\})\)", decoded, re.S)
        if m:
            try:
                data = json.loads(m.group(1))
                for source in data.get("sources", []):
                    for u in source.get("images", []) or []:
                        u_str = str(u).strip()
                        if u_str:
                            urls.append(urljoin(url, u_str))
            except Exception:
                pass

    if not urls:
        raise ValueError("No chapter images found. The site may have changed or blocked the request.")
    return urls


def download_image(page_url: str, referer: str, destination: Path, label: str, fetch=request) -> None:
    temporary = destination.with_suffix(destination.suffix + ".part")
    try:
        with fetch(page_url, referer=referer) as response:
            content_type = response.headers.get_content_type()
            if not content_type.startswith("image/") and content_type != "application/octet-stream":
                raise ValueError(f"{label} did not return an image.")
            with temporary.open("wb") as output:
                while chunk := response.read(256 * 1024):
                    output.write(chunk)
        if not temporary.stat().st_size:
            raise ValueError(f"{label} was empty.")
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def save_chapter(url: str, report, base_root: Path | None = None) -> None:
    if base_root is None:
        base_root = get_adaptive_save_root()
    if is_nhentai_link(url):
        save_nhentai(url, report, base_root)
        return
    target = chapter_location(url, base_root)
    pages = chapter_pages(url)
    target.mkdir(parents=True, exist_ok=True)
    width = max(3, len(str(len(pages))))
    for number, page in enumerate(pages, 1):
        suffix = Path(urlparse(page).path).suffix.lower()
        if suffix not in (".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif"):
            suffix = ".jpg"
        destination = target / f"{number:0{width}d}{suffix}"
        if destination.is_file() and destination.stat().st_size:
            report(f"Skipped existing {destination.name} ({number}/{len(pages)})")
            continue
        download_image(page, url, destination, f"Page {number}")
        report(f"Saved {destination.name} ({number}/{len(pages)})")
    report(f"Finished: {target}")


def save_nhentai(url: str, report, base_root: Path) -> None:
    gallery_id, start_page = parse_nhentai_url(url)
    begin = start_page or 1
    referer = f"https://nhentai.net/g/{gallery_id}/"
    gallery = None
    failures = []
    for label, resolver in (("gallery API", nhentai_gallery_api), ("gallery page", nhentai_gallery_page)):
        try:
            gallery = resolver(gallery_id)
            break
        except (HTTPError, URLError, OSError, ValueError) as error:
            failures.append(f"{label}: {error}")
    if gallery is None:
        report("Page listing unavailable (" + "; ".join(failures) + "); trying the page reader instead.")
        save_nhentai_by_reader(gallery_id, begin, referer, report, base_root)
        return

    total = gallery["num_pages"]
    pages = [page for page in gallery["pages"] if page[0] >= begin]
    if not pages:
        report(f"Gallery {gallery_id}: page {begin} is beyond the last page ({total}).")
        return
    report(f"Gallery {gallery_id}: {total} pages — downloading pages {pages[0][0]}–{pages[-1][0]}.")

    target = nhentai_target_dir(gallery_id, gallery["title"], base_root)
    migrate_legacy_gallery_folder(target, gallery_id, base_root)
    target.mkdir(parents=True, exist_ok=True)
    width = max(3, len(str(total)))
    for number, image_url, suffix in pages:
        destination = target / f"{number:0{width}d}{suffix}"
        if destination.is_file() and destination.stat().st_size:
            report(f"Skipped existing {destination.name} ({number}/{total})")
            continue
        try:
            download_image(image_url, referer, destination, f"Page {number}", fetch=nhentai_open)
        except HTTPError as error:
            if error.code != 404:
                raise
            scraped, _ = nhentai_reader_page(gallery_id, number)
            if not scraped:
                raise
            download_image(scraped, referer, destination, f"Page {number}", fetch=nhentai_open)
        report(f"Saved {destination.name} ({number}/{total})")
    report(f"Finished: {target}")


def save_nhentai_by_reader(gallery_id: int, begin: int, referer: str, report, base_root: Path) -> None:
    target: Path | None = None
    total_saved = 0
    number = begin
    try:
        while number <= 2000:
            image_url, title = nhentai_reader_page(gallery_id, number)
            if not image_url:
                break
            if target is None:
                target = nhentai_target_dir(gallery_id, title, base_root)
                target.mkdir(parents=True, exist_ok=True)
                report(f"Gallery {gallery_id}: reader fallback from page {begin} ({title or 'untitled'}).")
            suffix = Path(urlparse(image_url).path).suffix.lower() or ".jpg"
            destination = target / f"{number:03d}{suffix}"
            if destination.is_file() and destination.stat().st_size:
                report(f"Skipped existing {destination.name} (page {number})")
            else:
                download_image(image_url, referer, destination, f"Page {number}", fetch=nhentai_open)
                total_saved += 1
                report(f"Saved {destination.name} (page {number})")
            number += 1
    except HTTPError as error:
        if error.code != 404:
            raise
    if target is None:
        raise ValueError(f"Gallery {gallery_id}: no readable pages found (the site may be blocking automated requests).")
    report(f"Finished: {target} ({total_saved} new)")


class DownloaderWindow:
    def __init__(self):
        self.window = tk.Tk()
        self.window.title("Chapter Downloader — Rawkuma / NHentai")
        self.window.geometry("780x580")
        self.messages: queue.Queue = queue.Queue()
        self.save_root_var = tk.StringVar(value=str(get_adaptive_save_root()))

        frame = ttk.Frame(self.window, padding=16)
        frame.pack(fill="both", expand=True)

        ttk.Label(frame, text="Chapter or gallery links (one per line):").pack(anchor="w")
        self.links = tk.Text(frame, height=5, wrap="word")
        self.links.pack(fill="x", pady=(4, 10))

        # Adaptive Save Folder UI
        folder_frame = ttk.Frame(frame)
        folder_frame.pack(fill="x", pady=(0, 10))
        ttk.Label(folder_frame, text="Save directory:").pack(side="left")
        self.folder_entry = ttk.Entry(folder_frame, textvariable=self.save_root_var)
        self.folder_entry.pack(side="left", fill="x", expand=True, padx=6)
        ttk.Button(folder_frame, text="Browse…", command=self.browse_folder).pack(side="left")
        ttk.Button(folder_frame, text="Open folder", command=self.open_folder).pack(side="left", padx=6)

        bar = ttk.Frame(frame)
        bar.pack(fill="x", pady=(0, 10))
        self.button = ttk.Button(bar, text="Download chapters", command=self.start)
        self.button.pack(side="left")

        ttk.Label(frame, text="Progress & Logs:").pack(anchor="w")
        self.log = tk.Text(frame, state="disabled", wrap="word", font=("Consolas", 9))
        self.log.pack(fill="both", expand=True, pady=(4, 0))
        self.window.after(100, self.drain)

    def browse_folder(self):
        initial = self.save_root_var.get()
        if not Path(initial).exists():
            initial = str(Path.home() / "Downloads")
        chosen = filedialog.askdirectory(
            parent=self.window,
            title="Choose folder to save chapters into",
            initialdir=initial,
            mustexist=False,
        )
        if chosen:
            self.save_root_var.set(chosen)
            save_settings(chosen)

    def open_folder(self):
        dest = Path(self.save_root_var.get().strip())
        dest.mkdir(parents=True, exist_ok=True)
        try:
            os.startfile(dest)
        except Exception as e:
            messagebox.showerror("Cannot open folder", str(e))

    def start(self):
        urls = [line.strip() for line in self.links.get("1.0", "end").splitlines() if line.strip()]
        if not urls:
            messagebox.showinfo("Chapter links", "Paste at least one chapter link.")
            return
        save_root = Path(self.save_root_var.get().strip() or get_adaptive_save_root())
        save_settings(save_root)
        self.button.config(state="disabled")
        threading.Thread(target=self.download, args=(urls, save_root), daemon=True).start()

    def download(self, urls: list[str], save_root: Path):
        for url in urls:
            self.messages.put(f"Reading {url}")
            try:
                save_chapter(url, self.messages.put, save_root)
            except (ValueError, OSError, HTTPError, URLError) as error:
                self.messages.put(f"Failed: {error}")
        self.messages.put(None)

    def drain(self):
        while not self.messages.empty():
            item = self.messages.get_nowait()
            if item is None:
                self.button.config(state="normal")
                continue
            self.log.config(state="normal")
            self.log.insert("end", item + "\n")
            self.log.see("end")
            self.log.config(state="disabled")
        self.window.after(100, self.drain)


def main():
    DownloaderWindow().window.mainloop()


if __name__ == "__main__":
    main()
