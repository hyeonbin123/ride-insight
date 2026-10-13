# [테스트 묶음] rides.py(라이딩 메타데이터 ↔ CSV 짝짓기)의 단위 테스트. UTC와 현지 시각의 차이, 하루 두 번 탄
# 라이딩, 후보가 둘인 경우, 15분 배수가 아닌 차이, 시계열 없는 라이딩, 파워 플래그 위치, 파일 이름·마지막 secs
# 파싱을 확인한다.
# [왜 중요한가] 잘못 짝지으면 엉뚱한 CSV를 GC 값과 비교하게 돼 파리티 결과 전체가 오염된다. 매핑
# 규칙(docs/experiments.md 1단계)의 각 조건을 하나씩 고정한다.

from ride_insight_ml import rides


# 합성 라이딩 메타데이터. workout_time은 GC처럼 소수 5자리 문자열로 넣고, 기본 플래그는 파워가 있는(4번째 글자
# P) 라이딩이다.
def _ride(date, workout_time, flags="TDSPHC-AGL-----"):
    return {"date": date, "sport": "Bike", "data": flags, "METRICS": {"workout_time": f"{workout_time:.5f}"}}


# UTC 08:00 라이딩이 현지 10:00 이름의 CSV(+2시간)와 짝지어지고 오프셋 7,200초가 기록되는지 확인한다.
def test_matches_by_offset_and_duration():
    r = [_ride("2020/06/01 08:00:00 UTC", 3600)]
    csvs = {"2020_06_01_10_00_00.csv": 3599.0}  # local time = UTC + 2 h
    entries, counters = rides.match_rides(r, csvs)
    assert entries[0]["csv"] == "2020_06_01_10_00_00.csv"
    assert entries[0]["offset_secs"] == 7200
    assert counters["matched"] == 1


# 같은 날 두 라이딩(1시간, 30분)이 각각 길이가 맞는 CSV와 짝지어지는지 확인한다. 시각 조건만으로는 둘 다
# 후보가 될 수 있어 길이 조건이 구분해 준다.
def test_duration_disambiguates_two_rides_on_one_day():
    r = [_ride("2020/06/01 06:00:00 UTC", 3600), _ride("2020/06/01 16:00:00 UTC", 1800)]
    csvs = {"2020_06_01_08_00_00.csv": 3600.0, "2020_06_01_18_00_00.csv": 1790.0}
    entries, counters = rides.match_rides(r, csvs)
    assert [e["csv"] for e in entries] == ["2020_06_01_08_00_00.csv", "2020_06_01_18_00_00.csv"]
    assert counters["matched"] == 2


# 시각·길이 조건을 모두 만족하는 CSV가 둘이면 짝짓지 않고 '모호'로 세야 한다. 틀린 짝보다 버리는 쪽을 택한
# 규칙의 확인이다.
def test_ambiguous_when_two_csvs_fit():
    r = [_ride("2020/06/01 06:00:00 UTC", 3600)]
    csvs = {"2020_06_01_08_00_00.csv": 3600.0, "2020_06_01_09_00_00.csv": 3610.0}
    entries, counters = rides.match_rides(r, csvs)
    assert entries[0]["csv"] is None
    assert counters["ambiguous"] == 1


# 차이가 7분(15분 배수 아님)이거나 길이가 크게 다르면(2,000초 vs 3,600초) 미매칭이어야 한다.
def test_non_quarter_hour_offset_and_wrong_duration_do_not_match():
    r = [_ride("2020/06/01 06:00:00 UTC", 3600)]
    assert rides.match_rides(r, {"2020_06_01_06_07_00.csv": 3600.0})[1]["unmatched"] == 1
    assert rides.match_rides(r, {"2020_06_01_07_00_00.csv": 2000.0})[1]["unmatched"] == 1


# 플래그가 전부 '-'인 라이딩은 이름이 같은 CSV가 있어도 짝짓지 않고 no_data로 센다.
def test_no_data_rides_are_not_matched():
    r = [_ride("2020/06/01 06:00:00 UTC", 3600, flags="---------------")]
    entries, counters = rides.match_rides(r, {"2020_06_01_06_00_00.csv": 3600.0})
    assert entries[0]["match"] == "no_data"
    assert entries[0]["power_flag"] is False
    assert counters["no_data"] == 1


# 파워 플래그는 정확히 4번째 글자의 'P'로만 판정하고, 빈 문자열·None은 False여야 한다.
def test_power_flag_position():
    assert rides.has_power_flag("TDSPHC-AGL-----")
    assert not rides.has_power_flag("TDS-HC-A-L-----")
    assert not rides.has_power_flag("")
    assert not rides.has_power_flag(None)


# CSV 이름 파싱(형식이 다른 INDEX.csv는 None)과 마지막 secs 읽기(헤더만 있으면 None, 'Infinity'는 None, 끝의
# 빈 줄은 무시)를 확인한다.
def test_csv_name_and_last_secs_parsing():
    assert rides.parse_csv_name("2020_06_01_08_00_00.csv").hour == 8
    assert rides.parse_csv_name("INDEX.csv") is None
    assert rides.last_secs_of_csv("secs,km\n0,0\n5,0.1\n10,0.2\n") == 10.0
    assert rides.last_secs_of_csv("secs,km\n") is None
    assert rides.last_secs_of_csv("secs,km\n0,0\nInfinity,1\n") is None
    assert rides.last_secs_of_csv("secs,km\n0,0\n5,0.1\n\n\n") == 5.0
