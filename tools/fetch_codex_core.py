#!/usr/bin/env python3
"""获取固定版本官方手机原型组件；仅提取并校验所需可执行文件。"""

import base64
import hashlib
import shutil
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "app/src/main/jniLibs/arm64-v8a/libcodex.so"
URL = "https://registry.npmjs.org/@openai/codex/-/codex-0.159.2-linux-arm64.tgz"
PACKAGE_SHA512 = "Pm0W2PnFeTEqPx+AOIwgOVB4dBtY24cuD0tx1hjCor60CbSAT7WBPZVNUINSrTCDrMndvQC67p71ZEz+gmPPkA=="
CORE_SHA256 = "113aa5d5952a6fd3950a88323219747d0c91978ebee948509a540c8a460e59a3"
MEMBER = "package/vendor/aarch64-unknown-linux-musl/bin/codex"


def digest(path: Path, algorithm: str) -> bytes:
    result = hashlib.new(algorithm)
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            result.update(block)
    return result.digest()


def main() -> None:
    if DESTINATION.exists():
        if digest(DESTINATION, "sha256").hex() != CORE_SHA256:
            raise SystemExit("现有组件校验不匹配，已停止，未覆盖文件。")
        print("官方组件已存在，SHA256 校验通过。")
        return
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="codex-quota-core-") as temporary:
        directory = Path(temporary)
        package = directory / "core.tgz"
        print("正在下载官方 Codex 0.159.2 ARM64 包…")
        with urllib.request.urlopen(URL, timeout=60) as response, package.open("wb") as output:
            shutil.copyfileobj(response, output)
        if base64.b64encode(digest(package, "sha512")).decode() != PACKAGE_SHA512:
            raise SystemExit("官方包完整性校验失败，未安装组件。")
        core = directory / "codex"
        with tarfile.open(package, "r:gz") as archive:
            with archive.extractfile(MEMBER) as source, core.open("wb") as output:
                shutil.copyfileobj(source, output)
        if digest(core, "sha256").hex() != CORE_SHA256:
            raise SystemExit("组件 SHA256 校验失败，未安装组件。")
        shutil.copyfile(core, DESTINATION)
    print("官方组件准备完成，完整性校验通过。")


if __name__ == "__main__":
    main()
