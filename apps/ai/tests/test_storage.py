import pytest

from khmerdub_ai.storage import UnsafePathError, atomic_write_bytes, resolve_in_storage


@pytest.mark.parametrize("bad", [
    "", "/etc/passwd", "D:/KhmerDub/data/x.mp4", "c:x", "projects\\a\\b.mp4",
    "../outside", "projects/../../outside", "projects//double", "./here", "projects/.",
])
def test_rejects_unsafe_paths(storage_root, bad):
    with pytest.raises(UnsafePathError):
        resolve_in_storage(storage_root, bad)


def test_resolves_inside_root(storage_root):
    p = resolve_in_storage(storage_root, "projects/abc/original/movie..final.mp4")
    assert p.is_relative_to(storage_root.resolve())
    assert p.name == "movie..final.mp4"


def test_atomic_write_creates_and_replaces_without_leftovers(storage_root):
    target = resolve_in_storage(storage_root, "temp/unit/atomic.bin")
    atomic_write_bytes(target, b"first")
    atomic_write_bytes(target, b"second")
    assert target.read_bytes() == b"second"
    assert [f.name for f in target.parent.iterdir()] == ["atomic.bin"]
