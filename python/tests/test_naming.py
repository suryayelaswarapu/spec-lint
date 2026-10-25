import pytest

from speclint.naming import to_camel, to_kebab, to_pascal, unique, words


@pytest.mark.parametrize(
    ("name", "camel", "pascal", "kebab"),
    [
        ("GetForecast", "getForecast", "GetForecast", "get-forecast"),
        (
            "create_alert_subscription",
            "createAlertSubscription",
            "CreateAlertSubscription",
            "create-alert-subscription",
        ),
        ("Current_Conditions", "currentConditions", "CurrentConditions", "current-conditions"),
        ("NDFDgenByDay", "ndfDgenByDay", "NDFDgenByDay", "ndf-dgen-by-day"),
        ("forecast-v2", "forecastV2", "ForecastV2", "forecast-v2"),
    ],
)
def test_case_conversions(name: str, camel: str, pascal: str, kebab: str) -> None:
    assert to_camel(name) == camel
    assert to_pascal(name) == pascal
    assert to_kebab(name) == kebab


def test_empty_and_unique() -> None:
    assert words("--") == []
    assert to_camel("--") == "--"
    assert unique("a", {"a", "a2"}) == "a3"
    assert unique("b", {"a"}) == "b"
