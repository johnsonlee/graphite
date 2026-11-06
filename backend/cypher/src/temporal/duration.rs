use super::{error, temporal, Temporal};
use crate::{CypherResult, Value};
use indexmap::IndexMap;
use regex::Regex;
use std::fmt;

const MONTH_DAYS: f64 = 365.2425 / 12.0;
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct Duration {
    pub months: i64,
    pub days: i64,
    pub seconds: i64,
    pub nanos: i32,
}
fn component(map: &IndexMap<String, Value>, key: &str) -> CypherResult<f64> {
    match map.get(key) {
        None => Ok(0.0),
        Some(v) => v
            .as_f64()
            .filter(|v| v.is_finite())
            .ok_or_else(|| error(format!("Duration component {key} must be finite numeric"))),
    }
}
fn bounded(n: f64) -> CypherResult<i64> {
    if n.is_finite() && n >= i64::MIN as f64 && n < (i64::MAX as f64) {
        Ok(n as i64)
    } else {
        Err(error("Duration component out of range"))
    }
}
impl Duration {
    fn from_parts(months: f64, days: f64, seconds: f64, nanos: f64) -> CypherResult<Self> {
        Self::from_parts_with_rounding(months, days, seconds, nanos, true)
    }
    fn from_parts_with_rounding(
        months: f64,
        days: f64,
        seconds: f64,
        nanos: f64,
        round_nanos: bool,
    ) -> CypherResult<Self> {
        let m = bounded(months.trunc())?;
        let days = days + months.fract() * MONTH_DAYS;
        let d = bounded(days.trunc())?;
        let seconds = seconds + days.fract() * 86400.0;
        let s = bounded(seconds.trunc())?;
        let ns = nanos + seconds.fract() * 1_000_000_000.0;
        let ns = bounded(if round_nanos { ns.round() } else { ns.trunc() })?;
        let s = s
            .checked_add(ns.div_euclid(1_000_000_000))
            .ok_or_else(|| error("Duration seconds out of range"))?;
        Ok(Self {
            months: m,
            days: d,
            seconds: s,
            nanos: ns.rem_euclid(1_000_000_000) as i32,
        })
    }
    pub fn from_map(map: &IndexMap<String, Value>) -> CypherResult<Self> {
        Self::from_parts(
            component(map, "years")? * 12.0 + component(map, "months")?,
            component(map, "weeks")? * 7.0 + component(map, "days")?,
            component(map, "hours")? * 3600.0
                + component(map, "minutes")? * 60.0
                + component(map, "seconds")?,
            component(map, "milliseconds")? * 1_000_000.0
                + component(map, "microseconds")? * 1000.0
                + component(map, "nanoseconds")?,
        )
    }
    pub fn parse(text: &str) -> CypherResult<Self> {
        let pattern=Regex::new(r"^P(?:(?P<years>[+-]?(?:\d+(?:\.\d*)?|\.\d+))Y)?(?:(?P<months>[+-]?(?:\d+(?:\.\d*)?|\.\d+))M)?(?:(?P<weeks>[+-]?(?:\d+(?:\.\d*)?|\.\d+))W)?(?:(?P<days>[+-]?(?:\d+(?:\.\d*)?|\.\d+))D)?(?:T(?:(?P<hours>[+-]?(?:\d+(?:\.\d*)?|\.\d+))H)?(?:(?P<minutes>[+-]?(?:\d+(?:\.\d*)?|\.\d+))M)?(?:(?P<seconds>[+-]?(?:\d+(?:\.\d*)?|\.\d+))S)?)?$").unwrap();
        if let Some(captures) = pattern.captures(text) {
            let mut fields = IndexMap::new();
            for key in [
                "years", "months", "weeks", "days", "hours", "minutes", "seconds",
            ] {
                if let Some(n) = captures.name(key) {
                    fields.insert(
                        key.to_owned(),
                        Value::Float(
                            n.as_str()
                                .parse()
                                .map_err(|_| error("Invalid duration number"))?,
                        ),
                    );
                }
            }
            if fields.is_empty() {
                return Err(error("Duration requires components"));
            }
            return Self::from_map(&fields);
        }
        let pattern = Regex::new(r"^P(\d+)-(\d+)-(\d+)T(\d+):(\d+):([\d.]+)$").unwrap();
        if let Some(c) = pattern.captures(text) {
            let mut fields = IndexMap::new();
            for (n, key) in ["years", "months", "days", "hours", "minutes", "seconds"]
                .iter()
                .enumerate()
            {
                fields.insert(
                    (*key).to_owned(),
                    Value::Float(
                        c[n + 1]
                            .parse()
                            .map_err(|_| error("Invalid duration number"))?,
                    ),
                );
            }
            return Self::from_map(&fields);
        }
        Err(error("Invalid ISO duration"))
    }
    pub fn add(&self, other: &Self, sign: i64) -> CypherResult<Self> {
        let add = |a: i64, b: i64| {
            b.checked_mul(sign)
                .and_then(|b| a.checked_add(b))
                .ok_or_else(|| error("Duration arithmetic overflow"))
        };
        let nanos = add(i64::from(self.nanos), i64::from(other.nanos))?;
        Ok(Self {
            months: add(self.months, other.months)?,
            days: add(self.days, other.days)?,
            seconds: add(self.seconds, other.seconds)?
                .checked_add(nanos.div_euclid(1_000_000_000))
                .ok_or_else(|| error("Duration arithmetic overflow"))?,
            nanos: nanos.rem_euclid(1_000_000_000) as i32,
        })
    }
    pub fn scale(&self, scale: f64) -> CypherResult<Self> {
        Self::from_parts_with_rounding(
            self.months as f64 * scale,
            self.days as f64 * scale,
            self.seconds as f64 * scale,
            f64::from(self.nanos) * scale,
            false,
        )
    }
    pub fn property(&self, key: &str) -> Value {
        let n = match key {
            "years" => self.months / 12,
            "months" => self.months,
            "quarters" => self.months / 3,
            "weeks" => self.days / 7,
            "days" => self.days,
            "hours" => self.seconds / 3600,
            "minutes" => self.seconds / 60,
            "seconds" => self.seconds,
            "milliseconds" => self.seconds.saturating_mul(1000) + i64::from(self.nanos / 1_000_000),
            "microseconds" => self.seconds.saturating_mul(1_000_000) + i64::from(self.nanos / 1000),
            "nanoseconds" => self.seconds.saturating_mul(1_000_000_000) + i64::from(self.nanos),
            "monthsOfYear" => self.months % 12,
            "monthsOfQuarter" => self.months % 3,
            "quartersOfYear" => (self.months / 3) % 4,
            "daysOfWeek" => self.days % 7,
            "minutesOfHour" => (self.seconds / 60) % 60,
            "secondsOfMinute" => self.seconds % 60,
            "millisecondsOfSecond" => i64::from(self.nanos / 1_000_000),
            "microsecondsOfSecond" => i64::from(self.nanos / 1000),
            "nanosecondsOfSecond" => i64::from(self.nanos),
            _ => return Value::Null,
        };
        Value::Int(n)
    }
}
impl fmt::Display for Duration {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "P")?;
        let years = self.months / 12;
        let months = self.months % 12;
        if years != 0 {
            write!(f, "{years}Y")?
        }
        if months != 0 {
            write!(f, "{months}M")?
        }
        if self.days != 0 {
            write!(f, "{}D", self.days)?
        }
        let total = i128::from(self.seconds) * 1_000_000_000 + i128::from(self.nanos);
        if total != 0 || (self.months == 0 && self.days == 0) {
            write!(f, "T")?;
            let seconds = total / 1_000_000_000;
            let nanos = total % 1_000_000_000;
            let hours = seconds / 3600;
            let minutes = (seconds % 3600) / 60;
            let seconds = seconds % 60;
            if hours != 0 {
                write!(f, "{hours}H")?
            }
            if minutes != 0 {
                write!(f, "{minutes}M")?
            }
            if seconds != 0 || nanos != 0 || (hours == 0 && minutes == 0) {
                if nanos != 0 {
                    let fraction = format!("{:09}", nanos.abs())
                        .trim_end_matches('0')
                        .to_owned();
                    if seconds == 0 && nanos < 0 {
                        write!(f, "-0.{fraction}S")?
                    } else {
                        write!(f, "{seconds}.{fraction}S")?
                    }
                } else {
                    write!(f, "{seconds}S")?
                }
            }
        }
        Ok(())
    }
}
pub fn construct(args: &[Value]) -> CypherResult<Value> {
    if args.len() != 1 {
        return Err(error("duration expects one argument"));
    }
    match &args[0] {
        Value::Null => Ok(Value::Null),
        Value::Map(map) => Ok(temporal(Temporal::Duration(Duration::from_map(map)?))),
        Value::Str(text) => Ok(temporal(Temporal::Duration(Duration::parse(text)?))),
        Value::Temporal(t) if matches!(t.as_ref(), Temporal::Duration(_)) => Ok(args[0].clone()),
        _ => Err(error("duration expects a map, ISO string or duration")),
    }
}
