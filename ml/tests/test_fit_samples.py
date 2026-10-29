# [테스트 묶음] fit_samples.py(서버 FIT 디코더 테스트에 쓰는 샘플 FIT 9개 내려받기)의 단위 테스트. 네트워크
# 없이 urllib.request.urlopen을 대역으로 바꾸고, 저장소에 커밋된 사본(server/src/test/resources/fit/)을 기준
# 자료로 쓴다.
# [왜 중요한가] 서버 테스트는 커밋된 사본을 읽지만, 이 스크립트로 다시 받은 파일로 기대값(fit_expected.py)을
# 다시 만들면 내려받은 내용이 그대로 테스트 기준이 된다. 예전 스크립트는 python-fitparse의 master 브랜치에서
# 해시 확인 없이 받아, 업스트림 파일이 바뀌거나 잘못 받아도 알 수 없었다. 그래서 (1) 주소가 특정 커밋에
# 고정돼 있는지, (2) SAMPLES에 적은 SHA-256이 커밋된 사본과 같은지, (3) 해시가 다르면 파일을 남기지 않고
# 종료 코드 2로 끝나는지, (4) 이미 있는 파일도 해시로 확인하는지를 고정한다.

import hashlib
import io
import re
import shutil
from pathlib import Path

import pytest

from ride_insight_ml import fit_samples

# 커밋된 샘플 FIT 폴더. 이 파일(ml/tests/) 기준 두 단계 위가 저장소 뿌리다. CI의 ml 작업도 저장소 전체를
# 체크아웃하므로 같은 경로로 읽힌다.
FIXTURES = Path(__file__).resolve().parents[2] / "server" / "src" / "test" / "resources" / "fit"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


# urlopen 응답 대역. read와 with 문은 io.BytesIO가 제공하고, info()는 빈 헤더를 돌려준다(예전 코드의
# urlretrieve도 이 대역으로 끝까지 돌게 해, 고치기 전 실패가 대역의 빈틈이 아니라 해시 검사가 없어서임을
# 보이려는 것).
class _FakeResponse(io.BytesIO):
    def info(self) -> dict:
        return {}


# 커밋된 사본의 SHA-256이 SAMPLES에 적은 값과 같고, 폴더의 .fit 파일 이름과 SAMPLES의 이름이 정확히 같은지
# 확인한다. 픽스처를 바꾸거나 더하면서 SAMPLES를 고치지 않으면 여기서 잡힌다.
def test_committed_fixtures_match_the_pinned_hashes():
    on_disk = {p.name: _sha256(p) for p in FIXTURES.glob("*.fit")}
    assert len(on_disk) == 9
    assert on_disk == {name: s.sha256 for name, s in fit_samples.SAMPLES.items()}


# 받는 주소가 브랜치가 아니라 python-fitparse의 특정 커밋(40자리 SHA-1) 경로인지 확인한다. master처럼
# 움직이는 이름이면 같은 스크립트가 날마다 다른 파일을 받을 수 있다.
def test_source_is_pinned_to_a_commit():
    pinned = r"https://raw\.githubusercontent\.com/dtcooper/python-fitparse/[0-9a-f]{40}/tests/files/"
    assert re.fullmatch(pinned, fit_samples.BASE)


# 받은 파일의 해시가 SAMPLES와 다르면 그 파일을 지우고(.part도 남기지 않고) 종료 코드 2로 끝나는지 확인한다.
# 맞는 사본이 이미 있는 8개는 해시만 확인하고 다시 받지 않으므로 요청은 빠진 1개뿐이어야 하고, 그 요청은
# 고정 커밋 주소에 timeout 60초를 넘겨야 한다.
def test_download_with_a_wrong_hash_is_deleted_and_exits_2(tmp_path, monkeypatch, capsys):
    names = list(fit_samples.SAMPLES)
    missing = names[0]
    for name in names[1:]:
        shutil.copyfile(FIXTURES / name, tmp_path / name)
    requests: list = []

    def fake_urlopen(url, *args, **kwargs):
        requests.append((url, kwargs.get("timeout")))
        return _FakeResponse(b"not the fixture")

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    with pytest.raises(SystemExit) as e:
        fit_samples.main(["--dest", str(tmp_path)])
    assert e.value.code == 2
    assert requests == [(fit_samples.BASE + missing, 60)]
    assert not (tmp_path / missing).exists()
    assert not list(tmp_path.glob("*.part"))
    assert all(_sha256(tmp_path / n) == _sha256(FIXTURES / n) for n in names[1:])
    assert f"sha256 mismatch: {missing}" in capsys.readouterr().err


# 이미 있는 파일이라도 해시가 다르면(다른 커밋에서 받은 옛 사본 등) 그대로 쓰지 않고 .bad로 비켜 둔 뒤 다시
# 받는지 확인한다. 예전 코드는 이름만 있으면 건너뛰어 틀린 사본을 계속 썼다.
def test_existing_file_with_a_wrong_hash_is_set_aside_and_fetched_again(tmp_path, monkeypatch):
    names = list(fit_samples.SAMPLES)
    for name in names:
        shutil.copyfile(FIXTURES / name, tmp_path / name)
    stale = names[-1]
    (tmp_path / stale).write_bytes(b"stale copy from another revision")
    good = (FIXTURES / stale).read_bytes()
    monkeypatch.setattr("urllib.request.urlopen", lambda url, *a, **k: _FakeResponse(good))
    fit_samples.main(["--dest", str(tmp_path)])
    assert (tmp_path / stale).read_bytes() == good
    assert (tmp_path / stale).with_suffix(".bad").read_bytes() == b"stale copy from another revision"
    assert _sha256(tmp_path / stale) == fit_samples.SAMPLES[stale].sha256
