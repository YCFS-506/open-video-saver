"""Small desktop UI for saving public videos and image posts."""

from __future__ import annotations

import os
import sys
import io
import queue
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, ttk
from PIL import Image, ImageTk

from downloader import DownloadError, detect_platform, extract_url, list_qualities, save_video
from image_posts import load_previews
from platforms import platform_name


def fps_text(fps: float) -> str:
    if not fps:
        return "未标明"
    rounded = round(fps)
    return f"{rounded} 帧/秒" if abs(fps - rounded) < 0.1 else f"{fps:.1f} 帧/秒"


class App:
    def __init__(self) -> None:
        self.root = tk.Tk()
        self.root.title("公开作品保存（抖音 / 哔哩哔哩 / 小红书 / 快手）")
        self.root.geometry("840x610")
        self.root.minsize(740, 540)
        self.events: queue.Queue[tuple] = queue.Queue()
        self.running = False
        self.lookup_started: float | None = None
        self.lookup_platform: str | None = None
        self.lookup_task = "作品内容"
        self.loaded_url: str | None = None
        self.quality_rows: dict[str, dict] = {}
        self.pending_start = False

        frame = ttk.Frame(self.root, padding=18)
        frame.pack(fill="both", expand=True)

        ttk.Label(frame, text="粘贴抖音、哔哩哔哩、小红书或快手分享链接，也可以粘贴整段分享文案：").pack(anchor="w")
        self.share_text = tk.Text(frame, height=3, wrap="word", font=("Microsoft YaHei UI", 10))
        self.share_text.pack(fill="x", pady=(6, 5))
        self.platform_text = tk.StringVar(value="平台：等待粘贴链接")
        ttk.Label(frame, textvariable=self.platform_text).pack(anchor="w", pady=(0, 10))
        self.share_text.bind("<<Modified>>", self.on_share_changed)
        self.share_text.edit_modified(False)

        quality_header = ttk.Frame(frame)
        quality_header.pack(fill="x")
        ttk.Label(quality_header, text="此作品可选内容：").pack(side="left")
        self.load_button = ttk.Button(quality_header, text="读取作品", command=self.load_qualities)
        self.load_button.pack(side="right")

        quality_frame = ttk.Frame(frame)
        quality_frame.pack(fill="both", expand=True, pady=(6, 4))
        columns = ("level", "resolution", "fps", "codec", "size")
        self.quality_tree = ttk.Treeview(quality_frame, columns=columns, show="headings", height=9,
                                         selectmode="browse")
        headings = {"level": "画质", "resolution": "分辨率", "fps": "帧率",
                    "codec": "格式与兼容性", "size": "预计大小"}
        widths = {"level": 90, "resolution": 145, "fps": 100, "codec": 220, "size": 110}
        for column in columns:
            self.quality_tree.heading(column, text=headings[column])
            self.quality_tree.column(column, width=widths[column], anchor="center", stretch=column == "codec")
        self.quality_tree.pack(side="left", fill="both", expand=True)
        scrollbar = ttk.Scrollbar(quality_frame, orient="vertical", command=self.quality_tree.yview)
        scrollbar.pack(side="right", fill="y")
        self.quality_tree.configure(yscrollcommand=scrollbar.set)
        self.quality_tree.bind("<<TreeviewSelect>>", self.on_quality_selected)

        self.quality_note = tk.StringVar(value="点“读取作品”可查看视频画质，或图文作品的图片数量。")
        ttk.Label(frame, textvariable=self.quality_note, wraplength=790).pack(anchor="w", pady=(0, 12))

        ttk.Label(frame, text="保存到：").pack(anchor="w")
        folder_line = ttk.Frame(frame)
        folder_line.pack(fill="x", pady=(5, 12))
        program_folder = (Path(sys.executable).resolve().parent if getattr(sys, "frozen", False)
                          else Path(__file__).resolve().parent)
        self.folder = tk.StringVar(value=str(program_folder / "已下载视频"))
        ttk.Entry(folder_line, textvariable=self.folder).pack(side="left", fill="x", expand=True)
        ttk.Button(folder_line, text="选择文件夹", command=self.choose_folder).pack(side="left", padx=(8, 0))

        actions = ttk.Frame(frame)
        actions.pack(fill="x")
        self.start_button = ttk.Button(actions, text="开始下载", command=self.start)
        self.start_button.pack(side="left")
        self.images_button = ttk.Button(actions, text="预览并选择图片", command=self.choose_images)
        self.images_button.pack(side="left", padx=(8, 0))
        self.images_button.state(["disabled"])
        self.open_button = ttk.Button(actions, text="打开保存目录", command=self.open_folder)
        self.open_button.pack(side="left", padx=(8, 0))

        self.progress = ttk.Progressbar(frame, mode="determinate", maximum=100)
        self.progress.pack(fill="x", pady=(14, 8))
        self.status = tk.StringVar(value="等待粘贴链接。仅访问公开页面和接口，不读取账号 Cookie。")
        ttk.Label(frame, textvariable=self.status, wraplength=790).pack(anchor="w")

        self.root.after(100, self.poll_events)

    def on_share_changed(self, _event=None) -> None:
        if not self.share_text.edit_modified():
            return
        self.share_text.edit_modified(False)
        text = self.share_text.get("1.0", "end").strip()
        try:
            url = extract_url(text)
            platform = detect_platform(url)
            self.platform_text.set("平台：快手（优先读取官方手机网页）" if platform == "kuaishou"
                                   else f"平台：{platform_name(platform)}")
        except DownloadError:
            url = None
            self.platform_text.set("平台：未识别（支持抖音、哔哩哔哩、小红书、快手）")
        if self.loaded_url and url != self.loaded_url:
            self.loaded_url = None
            self.quality_rows.clear()
            self.quality_tree.delete(*self.quality_tree.get_children())
            self.quality_note.set("链接已变化，请点击“读取作品”查看新作品的内容。")

    def set_busy(self, busy: bool) -> None:
        self.running = busy
        state = ["disabled"] if busy else ["!disabled"]
        self.start_button.state(state)
        self.load_button.state(state)
        self.images_button.state(["disabled"] if busy or not any(
            option.get("kind") == "images" for option in self.quality_rows.values()) else ["!disabled"])

    def load_qualities(self) -> None:
        if self.running:
            return
        text = self.share_text.get("1.0", "end").strip()
        try:
            url = extract_url(text)
        except DownloadError as error:
            self.status.set(str(error))
            return
        platform = detect_platform(url)
        self.platform_text.set("平台：快手（优先读取官方手机网页）" if platform == "kuaishou"
                               else f"平台：{platform_name(platform)}")
        self.set_busy(True)
        self.lookup_started = time.monotonic()
        self.lookup_platform = platform
        self.lookup_task = "作品内容"
        self.status.set("正在读取快手官方网页作品信息…" if platform == "kuaishou"
                        else "正在读取作品内容…")
        self.progress["value"] = 0

        def work() -> None:
            try:
                self.events.put(("qualities", url, list_qualities(text)))
            except Exception as error:
                self.events.put(("error", str(error)))

        threading.Thread(target=work, daemon=True).start()

    def show_qualities(self, url: str, options: list[dict]) -> None:
        self.quality_tree.delete(*self.quality_tree.get_children())
        self.quality_rows.clear()
        self.loaded_url = url
        for index, option in enumerate(options):
            row_id = str(index)
            self.quality_rows[row_id] = option
            if option.get("kind") == "images":
                self.quality_tree.insert("", "end", iid=row_id, values=(
                    "图文", f"{option['image_count']} 张图片", "—", "图片 + 文案", "按实际文件",
                ))
                continue
            width, height = option["width"], option["height"]
            size = f"约 {option['bytes'] / (1024 * 1024):.1f} MB" if option["bytes"] else "未知"
            codec = ("通用格式（H.264）" if option["codec"] == "H.264" else
                     "省空间格式（H.265）" if option["codec"] == "H.265" else
                     "新格式（AV1）")
            quality_height = min(width, height)
            if 1070 <= quality_height <= 1080:
                quality_height = 1080
            self.quality_tree.insert("", "end", iid=row_id, values=(
                option.get("quality_label") or (f"{quality_height}p" if width and height else "待检测"),
                f"{width} × {height}" if width and height else "未标明",
                fps_text(option["fps"]), codec, size,
            ))
        recommended = next((str(index) for index, option in enumerate(options)
                            if sorted((option["width"], option["height"])) == [1080, 1920]
                            and option["codec"] == "H.264"), "0")
        self.quality_tree.selection_set(recommended)
        self.quality_tree.focus(recommended)
        self.quality_tree.see(recommended)
        self.on_quality_selected()
        source_note = "（第三方公开链接服务返回的播放流）" if options[0].get("via_public_link_service") else ""
        if options[0].get("kind") == "images":
            self.status.set(f"{platform_name(options[0]['platform'])}：找到 {options[0]['image_count']} 张图片和文案。请预览并选择图片。")
        else:
            self.status.set(f"{platform_name(options[0].get('platform', 'douyin'))}：找到 {len(options)} 个可选画质{source_note}。选择一行后点击“开始下载”。")

    def on_quality_selected(self, _event=None) -> None:
        selected = self.quality_tree.selection()
        if not selected or selected[0] not in self.quality_rows:
            return
        option = self.quality_rows[selected[0]]
        if option.get("kind") == "images":
            chosen = option.get("selected_indices")
            self.quality_note.set(f"已选 {len(chosen)} / {option['image_count']} 张图片；保存时保留原序号，并附文案和作品链接。"
                                  if chosen is not None else
                                  f"共 {option['image_count']} 张图片。先点击“预览并选择图片”查看每张实际像素尺寸。")
            return
        explanation = ("多数手机和电脑可直接播放。" if option["codec"] == "H.264" else
                       "文件通常更小，部分旧设备可能无法直接播放。" if option["codec"] == "H.265" else
                       "较新的编码格式，部分设备可能无法直接播放。")
        motion = ("60 帧画面更流畅。" if option["fps"] >= 50 else
                  "30 帧为常见流畅度。" if option["fps"] else "帧率未标明。")
        resolution = f"{option['width']} × {option['height']}" if option["width"] and option["height"] else "分辨率未标明"
        fps = fps_text(option["fps"])
        self.quality_note.set(f"已选 {resolution}、{fps}。"
                              f"{explanation}{motion} 文件大小为估算值。")

    def choose_folder(self) -> None:
        selected = filedialog.askdirectory(initialdir=self.folder.get())
        if selected:
            self.folder.set(selected)

    def open_folder(self) -> None:
        folder = Path(self.folder.get()).expanduser()
        folder.mkdir(parents=True, exist_ok=True)
        os.startfile(folder)

    def selected_option(self) -> dict | None:
        selected = self.quality_tree.selection()
        return self.quality_rows.get(selected[0]) if selected else None

    def choose_images(self) -> None:
        option = self.selected_option()
        if self.running or not option or option.get("kind") != "images":
            return
        if option.get("image_cache"):
            self.show_image_picker(option)
            return
        self.set_busy(True)
        self.lookup_started = time.monotonic()
        self.lookup_platform = option["platform"]
        self.lookup_task = "图片预览"
        self.status.set("正在载入图片预览和实际尺寸…")

        def work() -> None:
            try:
                load_previews(option)
                self.events.put(("previews", option))
            except Exception as error:
                self.events.put(("error", str(error)))

        threading.Thread(target=work, daemon=True).start()

    def show_image_picker(self, option: dict) -> None:
        dialog = tk.Toplevel(self.root)
        dialog.title("选择要保存的图片")
        dialog.geometry("780x620")
        dialog.minsize(600, 440)
        dialog.transient(self.root)
        dialog.grab_set()
        outer = ttk.Frame(dialog, padding=12)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="勾选要保存的图片。预览会缩小显示，保存的是网站返回的原始图片文件。",
                  wraplength=730).pack(anchor="w", pady=(0, 8))
        canvas = tk.Canvas(outer, highlightthickness=0)
        scroll = ttk.Scrollbar(outer, orient="vertical", command=canvas.yview)
        grid = ttk.Frame(canvas)
        grid.bind("<Configure>", lambda _event: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.create_window((0, 0), window=grid, anchor="nw")
        canvas.configure(yscrollcommand=scroll.set)
        canvas.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        wheel_delta = 0
        def on_wheel(event) -> str:
            nonlocal wheel_delta
            wheel_delta += event.delta
            steps = int(wheel_delta / 40)
            if steps:
                canvas.yview_scroll(-steps, "units")
                wheel_delta -= steps * 40
            return "break"
        # The toplevel is in every child widget's bindtags, so this works
        # while the pointer is over a preview, checkbox, or empty space.
        dialog.bind("<MouseWheel>", on_wheel)
        dialog.bind("<Button-4>", lambda _event: canvas.yview_scroll(-3, "units"))
        dialog.bind("<Button-5>", lambda _event: canvas.yview_scroll(3, "units"))
        vars_ = []
        photos = []
        previous = option.get("selected_indices")
        for index in range(option["image_count"]):
            card = ttk.Frame(grid, padding=6)
            card.grid(row=index // 4, column=index % 4, sticky="n", padx=4, pady=5)
            data, _mime = option["image_cache"][index]
            with Image.open(io.BytesIO(data)) as picture:
                picture.thumbnail((165, 165))
                photo = ImageTk.PhotoImage(picture.copy(), master=dialog)
            photos.append(photo)
            ttk.Label(card, image=photo).pack()
            selected = tk.BooleanVar(value=previous is None or index in previous)
            vars_.append(selected)
            width, height = option["image_sizes"][index]
            ttk.Checkbutton(card, text=f"第 {index + 1} 张 · {width}×{height}", variable=selected).pack(anchor="w")
        dialog.photos = photos
        bottom = ttk.Frame(dialog, padding=(12, 0, 12, 12))
        bottom.pack(fill="x")
        summary = tk.StringVar()
        def update_count(*_args) -> None:
            summary.set(f"已选 {sum(var.get() for var in vars_)} / {len(vars_)} 张")
        for variable in vars_:
            variable.trace_add("write", update_count)
        update_count()
        ttk.Label(bottom, textvariable=summary).pack(side="left")
        def set_all(value: bool) -> None:
            for variable in vars_:
                variable.set(value)
        ttk.Button(bottom, text="全选", command=lambda: set_all(True)).pack(side="left", padx=(12, 0))
        ttk.Button(bottom, text="全不选", command=lambda: set_all(False)).pack(side="left", padx=(6, 0))
        def confirm() -> None:
            chosen = [index for index, variable in enumerate(vars_) if variable.get()]
            if not chosen:
                summary.set("请至少选一张图片")
                return
            option["selected_indices"] = chosen
            dialog.destroy()
            self.on_quality_selected()
            self.status.set(f"已选择 {len(chosen)} 张图片，可以开始下载。")
            if self.pending_start:
                self.pending_start = False
                self.root.after(0, self.start)
        ttk.Button(bottom, text="确认选择", command=confirm).pack(side="right")
        def close() -> None:
            self.pending_start = False
            dialog.destroy()
        dialog.protocol("WM_DELETE_WINDOW", close)

    def start(self) -> None:
        if self.running:
            return
        text = self.share_text.get("1.0", "end").strip()
        try:
            url = extract_url(text)
        except DownloadError as error:
            self.status.set(str(error))
            return
        if self.loaded_url and url != self.loaded_url:
            self.status.set("分享链接已变化，请点击“读取作品”更新列表。")
            return
        folder = Path(self.folder.get()).expanduser()
        option = self.selected_option()
        if option is None:
            self.pending_start = True
            self.load_qualities()
            return
        if option.get("kind") == "images" and option.get("selected_indices") is None:
            self.pending_start = True
            self.choose_images()
            return
        quality = option["id"] if option else "1080"
        self.set_busy(True)
        self.progress["value"] = 0
        self.status.set("正在读取快手官方网页作品信息…"
                        if detect_platform(url) == "kuaishou" else "正在解析链接…")

        def work() -> None:
            try:
                saved = save_video(text, folder,
                                   lambda phase, done, total: self.events.put(("progress", phase, done, total)),
                                   quality=quality, cached_option=option)
                self.events.put(("success", str(saved)))
            except DownloadError as error:
                self.events.put(("error", str(error)))
            except Exception as error:
                self.events.put(("error", f"运行失败：{error}"))

        threading.Thread(target=work, daemon=True).start()

    def poll_events(self) -> None:
        try:
            while True:
                event = self.events.get_nowait()
                if event[0] == "progress":
                    _, phase, done, total = event
                    percent = int(done * 100 / total) if total else 0
                    self.status.set(f"{phase}：{percent}%" if total > 1 else phase)
                    self.progress["value"] = percent if total > 1 or phase == "完成" else 0
                elif event[0] == "qualities":
                    _, url, options = event
                    self.lookup_started = None
                    self.show_qualities(url, options)
                    self.set_busy(False)
                    if self.pending_start:
                        self.pending_start = False
                        self.root.after(0, self.start)
                elif event[0] == "previews":
                    self.lookup_started = None
                    self.set_busy(False)
                    self.show_image_picker(event[1])
                elif event[0] == "success":
                    self.status.set(f"已保存：{event[1]}")
                    self.progress["value"] = 100
                    self.set_busy(False)
                elif event[0] == "error":
                    self.lookup_started = None
                    self.pending_start = False
                    self.status.set(event[1])
                    self.set_busy(False)
        except queue.Empty:
            pass
        if self.running and self.lookup_started is not None:
            elapsed = int(time.monotonic() - self.lookup_started)
            if elapsed >= 2:
                self.status.set(f"正在读取{self.lookup_task}… 已等待 {elapsed} 秒")
        self.root.after(100, self.poll_events)

    def run(self) -> None:
        self.root.mainloop()


if __name__ == "__main__":
    App().run()
