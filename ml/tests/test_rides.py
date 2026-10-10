from ride_insight_ml import rides


def _ride(date, workout_time, flags="TDSPHC-AGL-----"):
    return {"date": date, "sport": "Bike", "data": flags, "METRICS": {"workout_time": f"{workout_time:.5f}"}}


def test_matches_by_offset_and_duration():
    r = [_ride("2020/06/01 08:00:00 UTC", 3600)]
    csvs = {"2020_06_01_10_00_00.csv": 3599.0}  # local time = UTC + 2 h
    entries, counters = rides.match_rides(r, csvs)
    assert entries[0]["csv"] == "2020_06_01_10_00_00.csv"
    assert entries[0]["offset_secs"] == 7200
    assert counters["matched"] == 1


def test_duration_disambiguates_two_rides_on_one_day():
    r = [_ride("2020/06/01 06:00:00 UTC", 3600), _ride("2020/06/01 16:00:00 UTC", 1800)]
    csvs = {"2020_06_01_08_00_00.csv": 3600.0, "2020_06_01_18_00_00.csv": 1790.0}
    entries, counters = rides.match_rides(r, csvs)
    assert [e["csv"] for e in entries] == ["2020_06_01_08_00_00.csv", "2020_06_01_18_00_00.csv"]
    assert counters["matched"] == 2


def test_ambiguous_when_two_csvs_fit():
    r = [_ride("2020/06/01 06:00:00 UTC", 3600)]
    csvs = {"2020_06_01_08_00_00.csv": 3600.0, "2020_06_01_09_00_00.csv": 3610.0}
    entries, counters = rides.match_rides(r, csvs)
    assert entries[0]["csv"] is None
    assert counters["ambiguous"] == 1


def test_non_quarter_hour_offset_and_wrong_duration_do_not_match():
    r = [_ride("2020/06/01 06:00:00 UTC", 3600)]
    assert rides.match_rides(r, {"2020_06_01_06_07_00.csv": 3600.0})[1]["unmatched"] == 1
    assert rides.match_rides(r, {"2020_06_01_07_00_00.csv": 2000.0})[1]["unmatched"] == 1


def test_no_data_rides_are_not_matched():
    r = [_ride("2020/06/01 06:00:00 UTC", 3600, flags="---------------")]
    entries, counters = rides.match_rides(r, {"2020_06_01_06_00_00.csv": 3600.0})
    assert entries[0]["match"] == "no_data"
    assert entries[0]["power_flag"] is False
    assert counters["no_data"] == 1


def test_power_flag_position():
    assert rides.has_power_flag("TDSPHC-AGL-----")
    assert not rides.has_power_flag("TDS-HC-A-L-----")
    assert not rides.has_power_flag("")
    assert not rides.has_power_flag(None)


def test_csv_name_and_last_secs_parsing():
    assert rides.parse_csv_name("2020_06_01_08_00_00.csv").hour == 8
    assert rides.parse_csv_name("INDEX.csv") is None
    assert rides.last_secs_of_csv("secs,km\n0,0\n5,0.1\n10,0.2\n") == 10.0
    assert rides.last_secs_of_csv("secs,km\n") is None
