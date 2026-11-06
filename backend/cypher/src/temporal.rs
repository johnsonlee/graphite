//! Typed ISO calendar and clock values. Text rendering is a boundary operation;
//! equality, ordering and field access retain temporal types throughout execution.
use crate::{CypherError, CypherResult, Value};
mod civil;
mod duration;
use chrono::{
    Datelike, FixedOffset, NaiveDate, NaiveDateTime, NaiveTime, Offset, TimeZone, Timelike, Utc,
};
use indexmap::IndexMap;
use std::{cmp::Ordering, fmt, sync::Arc};

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum Temporal {
    Duration(duration::Duration),
    ExtendedDate(civil::CivilDate),
    ExtendedLocalDateTime(civil::CivilDate, NaiveTime),
    Date(NaiveDate),
    LocalTime(NaiveTime),
    Time {
        time: NaiveTime,
        offset: i32,
    },
    LocalDateTime(NaiveDateTime),
    DateTime {
        local: NaiveDateTime,
        offset: i32,
        zone: Option<String>,
    },
}
fn error(message: impl Into<String>) -> CypherError {
    CypherError::Runtime(message.into())
}
fn integer(map: &IndexMap<String, Value>, key: &str, default: i64) -> CypherResult<i64> {
    match map.get(key) {
        None => Ok(default),
        Some(Value::Int(n)) => Ok(*n),
        _ => Err(error(format!(
            "Temporal component {key} must be an integer"
        ))),
    }
}
fn signed(n: i64) -> CypherResult<i32> {
    i32::try_from(n).map_err(|_| error("Temporal component out of range"))
}
fn unsigned(n: i64) -> CypherResult<u32> {
    u32::try_from(n).map_err(|_| error("Temporal component out of range"))
}
fn date(year: i32, month: u32, day: u32) -> CypherResult<NaiveDate> {
    NaiveDate::from_ymd_opt(year, month, day).ok_or_else(|| error("Invalid calendar date"))
}
fn temporal(value: Temporal) -> Value {
    Value::Temporal(Arc::new(value))
}
impl Temporal {
    fn civil_date(&self) -> Option<civil::CivilDate> {
        match self {
            Self::ExtendedDate(d) | Self::ExtendedLocalDateTime(d, _) => Some(*d),
            _ => self
                .date()
                .and_then(|d| civil::CivilDate::new(d.year(), d.month(), d.day()).ok()),
        }
    }
    pub fn date(&self) -> Option<NaiveDate> {
        match self {
            Self::Date(d) => Some(*d),
            Self::LocalDateTime(d) | Self::DateTime { local: d, .. } => Some(d.date()),
            _ => None,
        }
    }
    pub fn time(&self) -> Option<NaiveTime> {
        match self {
            Self::LocalTime(t) | Self::Time { time: t, .. } | Self::ExtendedLocalDateTime(_, t) => {
                Some(*t)
            }
            Self::LocalDateTime(d) | Self::DateTime { local: d, .. } => Some(d.time()),
            _ => None,
        }
    }
    pub fn offset(&self) -> Option<i32> {
        match self {
            Self::Time { offset, .. } | Self::DateTime { offset, .. } => Some(*offset),
            _ => None,
        }
    }
    pub fn compare(&self, other: &Self) -> Option<Ordering> {
        if matches!(self, Self::Date(_) | Self::ExtendedDate(_))
            && matches!(other, Self::Date(_) | Self::ExtendedDate(_))
        {
            return Some(self.civil_date()?.cmp(&other.civil_date()?));
        }
        if matches!(
            self,
            Self::LocalDateTime(_) | Self::ExtendedLocalDateTime(..)
        ) && matches!(
            other,
            Self::LocalDateTime(_) | Self::ExtendedLocalDateTime(..)
        ) {
            return Some(
                (self.civil_date()?, self.time()?).cmp(&(other.civil_date()?, other.time()?)),
            );
        }
        match (self, other) {
            (Self::Date(a), Self::Date(b)) => Some(a.cmp(b)),
            (Self::LocalTime(a), Self::LocalTime(b)) => Some(a.cmp(b)),
            (
                Self::Time {
                    time: a,
                    offset: ao,
                },
                Self::Time {
                    time: b,
                    offset: bo,
                },
            ) => Some(
                (
                    i64::from(a.num_seconds_from_midnight()) - i64::from(*ao),
                    a.nanosecond(),
                    ao,
                )
                    .cmp(&(
                        i64::from(b.num_seconds_from_midnight()) - i64::from(*bo),
                        b.nanosecond(),
                        bo,
                    )),
            ),
            (Self::LocalDateTime(a), Self::LocalDateTime(b)) => Some(a.cmp(b)),
            (
                Self::DateTime {
                    local: a,
                    offset: ao,
                    zone: az,
                },
                Self::DateTime {
                    local: b,
                    offset: bo,
                    zone: bz,
                },
            ) => Some(
                (
                    a.and_utc().timestamp() - i64::from(*ao),
                    a.nanosecond(),
                    ao,
                    az,
                )
                    .cmp(&(
                        b.and_utc().timestamp() - i64::from(*bo),
                        b.nanosecond(),
                        bo,
                        bz,
                    )),
            ),
            _ => None,
        }
    }
    pub fn order_compare(&self, other: &Self) -> Ordering {
        if let (Self::Duration(a), Self::Duration(b)) = (self, other) {
            let key = |d: &duration::Duration| {
                (
                    (i128::from(d.months) * 2_629_746
                        + i128::from(d.days) * 86400
                        + i128::from(d.seconds))
                        * 1_000_000_000
                        + i128::from(d.nanos),
                    d.months,
                    d.days,
                    d.seconds,
                    d.nanos,
                )
            };
            return key(a).cmp(&key(b));
        }
        self.compare(other)
            .unwrap_or_else(|| self.to_string().cmp(&other.to_string()))
    }
    pub fn property(&self, key: &str) -> Value {
        if let Self::Duration(d) = self {
            return d.property(key);
        }
        if let Some(d) = self.date() {
            let n = match key {
                "year" => Some(i64::from(d.year())),
                "month" => Some(i64::from(d.month())),
                "quarter" => Some(i64::from((d.month() - 1) / 3 + 1)),
                "day" => Some(i64::from(d.day())),
                "ordinalDay" => Some(i64::from(d.ordinal())),
                "week" => Some(i64::from(d.iso_week().week())),
                "weekYear" => Some(i64::from(d.iso_week().year())),
                "dayOfWeek" | "weekDay" => Some(i64::from(d.weekday().number_from_monday())),
                "dayOfQuarter" => Some(
                    d.signed_duration_since(
                        date(d.year(), (d.month() - 1) / 3 * 3 + 1, 1).unwrap(),
                    )
                    .num_days()
                        + 1,
                ),
                _ => None,
            };
            if let Some(n) = n {
                return Value::Int(n);
            }
        }
        if let Some(t) = self.time() {
            let n = match key {
                "hour" => Some(t.hour()),
                "minute" => Some(t.minute()),
                "second" => Some(t.second()),
                "millisecond" => Some(t.nanosecond() / 1_000_000),
                "microsecond" => Some(t.nanosecond() / 1_000),
                "nanosecond" => Some(t.nanosecond()),
                _ => None,
            };
            if let Some(n) = n {
                return Value::Int(i64::from(n));
            }
        }
        if let Some(offset) = self.offset() {
            match key {
                "offsetSeconds" => return Value::Int(i64::from(offset)),
                "offsetMinutes" => return Value::Int(i64::from(offset / 60)),
                "offset" => return Value::str(offset_string(offset)),
                "timezone" => {
                    return Value::str(match self {
                        Self::DateTime {
                            zone: Some(zone), ..
                        } => zone.clone(),
                        _ => offset_string(offset),
                    })
                }
                _ => {}
            }
        }
        if let Self::DateTime { local, offset, .. } = self {
            let seconds = local.and_utc().timestamp() - i64::from(*offset);
            match key {
                "epochSeconds" => return Value::Int(seconds),
                "epochMillis" => {
                    return Value::Int(seconds * 1000 + i64::from(local.nanosecond() / 1_000_000))
                }
                _ => {}
            }
        }
        Value::Null
    }
}
fn time_string(time: NaiveTime) -> String {
    if time.nanosecond() != 0 {
        format!("{}.{:09}", time.format("%H:%M:%S"), time.nanosecond())
            .trim_end_matches('0')
            .to_owned()
    } else if time.second() != 0 {
        time.format("%H:%M:%S").to_string()
    } else {
        time.format("%H:%M").to_string()
    }
}
fn offset_string(seconds: i32) -> String {
    if seconds == 0 {
        "Z".into()
    } else {
        let sign = if seconds < 0 { '-' } else { '+' };
        let seconds = seconds.unsigned_abs();
        if seconds.is_multiple_of(60) {
            format!("{sign}{:02}:{:02}", seconds / 3600, (seconds % 3600) / 60)
        } else {
            format!(
                "{sign}{:02}:{:02}:{:02}",
                seconds / 3600,
                (seconds % 3600) / 60,
                seconds % 60
            )
        }
    }
}
impl fmt::Display for Temporal {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Duration(d) => write!(f, "{d}"),
            Self::ExtendedDate(d) => write!(f, "{d}"),
            Self::ExtendedLocalDateTime(d, t) => write!(f, "{d}T{}", time_string(*t)),
            Self::Date(d) => write!(f, "{d}"),
            Self::LocalTime(t) => write!(f, "{}", time_string(*t)),
            Self::Time { time, offset } => {
                write!(f, "{}{}", time_string(*time), offset_string(*offset))
            }
            Self::LocalDateTime(d) => write!(f, "{}T{}", d.date(), time_string(d.time())),
            Self::DateTime {
                local,
                offset,
                zone,
            } => {
                write!(
                    f,
                    "{}T{}{}",
                    local.date(),
                    time_string(local.time()),
                    offset_string(*offset)
                )?;
                if let Some(zone) = zone {
                    write!(f, "[{zone}]")?;
                }
                Ok(())
            }
        }
    }
}
fn map_date(map: &IndexMap<String, Value>) -> CypherResult<NaiveDate> {
    let base = map.get("date").and_then(|v| {
        if let Value::Temporal(v) = v {
            v.date()
        } else {
            None
        }
    });
    let has_base = base.is_some();
    let base = base.unwrap_or(date(0, 1, 1)?);
    let default_year = if map.contains_key("week") {
        base.iso_week().year()
    } else {
        base.year()
    };
    let year = signed(integer(map, "year", i64::from(default_year))?)?;
    if map.contains_key("week") {
        let week = unsigned(integer(map, "week", 1)?)?;
        let weekday = integer(
            map,
            "dayOfWeek",
            if has_base {
                i64::from(base.weekday().number_from_monday())
            } else {
                1
            },
        )?;
        if !(1..=53).contains(&week) || !(1..=7).contains(&weekday) {
            return Err(error("Invalid ISO week date"));
        }
        let jan4 = date(year, 1, 4)?;
        let monday =
            jan4 - chrono::Duration::days(i64::from(jan4.weekday().num_days_from_monday()));
        return monday
            .checked_add_signed(chrono::Duration::days(
                (i64::from(week) - 1) * 7 + weekday - 1,
            ))
            .ok_or_else(|| error("Week date out of range"));
    }
    if map.contains_key("ordinalDay") {
        return NaiveDate::from_yo_opt(year, unsigned(integer(map, "ordinalDay", 1)?)?)
            .ok_or_else(|| error("Invalid ordinal date"));
    }
    if map.contains_key("quarter") {
        let quarter = unsigned(integer(map, "quarter", 1)?)?;
        if !(1..=4).contains(&quarter) {
            return Err(error("Invalid quarter"));
        }
        let first = date(year, (quarter - 1) * 3 + 1, 1)?;
        return first
            .checked_add_signed(chrono::Duration::days(
                integer(
                    map,
                    "dayOfQuarter",
                    if has_base {
                        base.signed_duration_since(date(
                            base.year(),
                            (base.month() - 1) / 3 * 3 + 1,
                            1,
                        )?)
                        .num_days()
                            + 1
                    } else {
                        1
                    },
                )? - 1,
            ))
            .ok_or_else(|| error("Quarter date out of range"));
    }
    if map.contains_key("dayOfWeek") {
        let weekday = integer(map, "dayOfWeek", 1)?;
        if !(1..=7).contains(&weekday) {
            return Err(error("Invalid day of week"));
        }
        return base
            .checked_add_signed(chrono::Duration::days(
                weekday - i64::from(base.weekday().number_from_monday()),
            ))
            .ok_or_else(|| error("Weekday out of range"));
    }
    date(
        year,
        unsigned(integer(map, "month", i64::from(base.month()))?)?,
        unsigned(integer(map, "day", i64::from(base.day()))?)?,
    )
}
fn parse_date(text: &str) -> CypherResult<NaiveDate> {
    if !text.is_ascii() {
        return Err(error("Invalid non-ASCII calendar date"));
    }
    if (text.len() == 7 && text.as_bytes()[4] == b'-')
        || (text.len() == 6 && text.bytes().all(|b| b.is_ascii_digit()))
    {
        let start = if text.len() == 7 { 5 } else { 4 };
        return date(
            text[..4].parse().map_err(|_| error("Invalid year"))?,
            text[start..].parse().map_err(|_| error("Invalid month"))?,
            1,
        );
    }
    for format in [
        "%Y-%m-%d",
        "%Y%m%d",
        "%Y-%j",
        "%Y%j",
        "%G-W%V-%u",
        "%GW%V%u",
    ] {
        if let Ok(d) = NaiveDate::parse_from_str(text, format) {
            return Ok(d);
        }
    }
    if text.len() == 4 {
        return date(text.parse().map_err(|_| error("Invalid year"))?, 1, 1);
    }
    if text.len() == 7 && text.as_bytes()[4] == b'-' {
        return date(
            text[..4].parse().map_err(|_| error("Invalid year"))?,
            text[5..].parse().map_err(|_| error("Invalid month"))?,
            1,
        );
    }
    if text.len() == 6 && text.bytes().all(|b| b.is_ascii_digit()) {
        return date(
            text[..4].parse().map_err(|_| error("Invalid year"))?,
            text[4..].parse().map_err(|_| error("Invalid month"))?,
            1,
        );
    }
    if text.contains('W') {
        let full = format!("{}{}", text, if text.contains('-') { "-1" } else { "1" });
        for format in ["%G-W%V-%u", "%GW%V%u"] {
            if let Ok(d) = NaiveDate::parse_from_str(&full, format) {
                return Ok(d);
            }
        }
        return Err(error("Invalid ISO week date"));
    }
    if let Some((year, quarter)) = text.split_once("-Q").or_else(|| text.split_once('Q')) {
        let mut parts = quarter.split('-');
        let q: u32 = parts
            .next()
            .unwrap()
            .parse()
            .map_err(|_| error("Invalid quarter"))?;
        let days: i64 = parts
            .next()
            .unwrap_or("1")
            .parse()
            .map_err(|_| error("Invalid day of quarter"))?;
        if !(1..=4).contains(&q) {
            return Err(error("Invalid quarter"));
        }
        return date(
            year.parse().map_err(|_| error("Invalid year"))?,
            (q - 1) * 3 + 1,
            1,
        )?
        .checked_add_signed(chrono::Duration::days(days - 1))
        .ok_or_else(|| error("Invalid quarter date"));
    }
    Err(error(format!("Invalid date: {text}")))
}
fn map_time(map: &IndexMap<String, Value>) -> CypherResult<NaiveTime> {
    let base = map
        .get("time")
        .and_then(|v| {
            if let Value::Temporal(v) = v {
                v.time()
            } else {
                None
            }
        })
        .unwrap_or(NaiveTime::MIN);
    let ms = integer(map, "millisecond", 0)?;
    let us = integer(map, "microsecond", 0)?;
    let ns = integer(
        map,
        "nanosecond",
        if ms == 0 && us == 0 {
            i64::from(base.nanosecond())
        } else {
            0
        },
    )?;
    let nanos = ms
        .checked_mul(1_000_000)
        .and_then(|v| us.checked_mul(1_000).and_then(|u| v.checked_add(u)))
        .and_then(|v| v.checked_add(ns))
        .ok_or_else(|| error("Clock fraction out of range"))?;
    if !(0..1_000_000_000).contains(&nanos) {
        return Err(error("Clock fraction out of range"));
    }
    NaiveTime::from_hms_nano_opt(
        unsigned(integer(map, "hour", i64::from(base.hour()))?)?,
        unsigned(integer(map, "minute", i64::from(base.minute()))?)?,
        unsigned(integer(map, "second", i64::from(base.second()))?)?,
        unsigned(nanos)?,
    )
    .ok_or_else(|| error("Invalid clock time"))
}
fn parse_time(text: &str) -> CypherResult<NaiveTime> {
    let text = text.strip_prefix('T').unwrap_or(text);
    for format in ["%H:%M:%S%.f", "%H:%M", "%H%M%S%.f", "%H%M", "%H"] {
        if let Ok(t) = NaiveTime::parse_from_str(text, format) {
            return Ok(t);
        }
    }
    if text.len() == 2 {
        return NaiveTime::from_hms_opt(text.parse().map_err(|_| error("Invalid hour"))?, 0, 0)
            .ok_or_else(|| error("Invalid hour"));
    }
    Err(error(format!("Invalid local time: {text}")))
}
fn parse_offset(text: &str, local: NaiveDateTime) -> CypherResult<(i32, Option<String>)> {
    if text == "Z" || text == "UTC" || text == "+00:00" {
        return Ok((0, None));
    }
    if text.starts_with(['+', '-']) {
        let sign = if text.starts_with('-') { -1 } else { 1 };
        let clean = text[1..].replace(':', "");
        let n: i32 = clean
            .parse()
            .map_err(|_| error("Invalid time zone offset"))?;
        let seconds = match clean.len() {
            2 => n * 3600,
            4 => (n / 100) * 3600 + (n % 100) * 60,
            6 => (n / 10000) * 3600 + ((n / 100) % 100) * 60 + n % 100,
            _ => return Err(error("Invalid time zone offset")),
        };
        if FixedOffset::east_opt(sign * seconds).is_none() {
            return Err(error("Invalid time zone offset"));
        }
        return Ok((sign * seconds, None));
    }
    let zone: chrono_tz::Tz = text
        .parse()
        .map_err(|_| error(format!("Unknown time zone: {text}")))?;
    let zoned = zone
        .from_local_datetime(&local)
        .earliest()
        .ok_or_else(|| error("Local datetime falls in time zone gap"))?;
    Ok((
        zoned.offset().fix().local_minus_utc(),
        Some(text.to_owned()),
    ))
}
fn temporal_argument(value: &Value) -> CypherResult<&Temporal> {
    if let Value::Temporal(value) = value {
        Ok(value)
    } else {
        Err(error("Expected temporal value"))
    }
}
fn build(kind: &str, arg: Option<&Value>) -> CypherResult<Value> {
    if arg.is_some_and(Value::is_null) {
        return Ok(Value::Null);
    }
    let mut map = IndexMap::new();
    match arg {
        Some(Value::Map(fields)) => map = fields.as_ref().clone(),
        Some(Value::Temporal(t)) => {
            if let Some(d) = t.date() {
                map.insert("date".into(), temporal(Temporal::Date(d)));
            }
            if let Some(time) = t.time() {
                map.insert("time".into(), temporal(Temporal::LocalTime(time)));
            }
            if let Some(offset) = t.offset() {
                map.insert(
                    "timezone".into(),
                    Value::str(match t.as_ref() {
                        Temporal::DateTime {
                            zone: Some(zone), ..
                        } => zone.clone(),
                        _ => offset_string(offset),
                    }),
                );
            }
        }
        Some(Value::Str(text)) => {
            if kind == "date" || kind == "localdatetime" {
                let (calendar, clock) = text
                    .split_once('T')
                    .map(|(d, t)| (d, Some(t)))
                    .unwrap_or((text.as_ref(), None));
                if let Ok(civil) = civil::CivilDate::parse(calendar) {
                    if NaiveDate::from_ymd_opt(civil.year, civil.month, civil.day).is_none() {
                        return Ok(temporal(if kind == "date" {
                            Temporal::ExtendedDate(civil)
                        } else {
                            Temporal::ExtendedLocalDateTime(
                                civil,
                                clock.map(parse_time).transpose()?.unwrap_or(NaiveTime::MIN),
                            )
                        }));
                    }
                }
                if kind == "localdatetime" && clock.is_none() {
                    if let Ok(d) = parse_date(calendar) {
                        return Ok(temporal(Temporal::LocalDateTime(
                            d.and_time(NaiveTime::MIN),
                        )));
                    }
                }
            }
            if kind == "date" {
                return Ok(temporal(Temporal::Date(parse_date(text)?)));
            }
            let (body, zone) = if text.ends_with(']') {
                let (body, zone) = text
                    .rsplit_once('[')
                    .ok_or_else(|| error("Invalid zone suffix"))?;
                (body, Some(zone.trim_end_matches(']')))
            } else {
                (text.as_ref(), None)
            };
            let (datepart, timepart) = if let Some((d, t)) = body.split_once('T') {
                (Some(d), t)
            } else {
                (None, body)
            };
            let mut clock = timepart;
            let mut offset = zone;
            if let Some(pos) = timepart.char_indices().find_map(|(i, c)| {
                if c == '+' || c == '-' || c == 'Z' {
                    Some(i)
                } else {
                    None
                }
            }) {
                clock = &timepart[..pos];
                if offset.is_none() {
                    offset = Some(&timepart[pos..])
                }
            }
            let time = if clock.is_empty() && datepart.is_some() {
                NaiveTime::MIN
            } else {
                parse_time(clock)?
            };
            if let Some(datepart) = datepart {
                map.insert(
                    "date".into(),
                    temporal(Temporal::Date(parse_date(datepart)?)),
                );
            }
            map.insert("time".into(), temporal(Temporal::LocalTime(time)));
            if let Some(offset) = offset {
                map.insert("timezone".into(), Value::str(offset));
            }
        }
        None => {
            let now = Utc::now().naive_utc();
            map.insert("date".into(), temporal(Temporal::Date(now.date())));
            map.insert("time".into(), temporal(Temporal::LocalTime(now.time())));
        }
        _ => {
            return Err(error(
                "Temporal constructor requires a map, string or temporal value",
            ))
        }
    }
    if let Some(Value::Temporal(t)) = map.get("datetime").cloned() {
        if let Some(d) = t.date() {
            map.entry("date".into())
                .or_insert_with(|| temporal(Temporal::Date(d)));
        }
        if let Some(time) = t.time() {
            map.entry("time".into())
                .or_insert_with(|| temporal(Temporal::LocalTime(time)));
        }
        if let Some(offset) = t.offset() {
            map.entry("timezone".into()).or_insert_with(|| {
                Value::str(match t.as_ref() {
                    Temporal::DateTime {
                        zone: Some(zone), ..
                    } => zone.clone(),
                    _ => offset_string(offset),
                })
            });
        }
    }
    let d = map_date(&map)?;
    let t = map_time(&map)?;
    Ok(temporal(match kind {
        "date" => Temporal::Date(d),
        "localtime" => Temporal::LocalTime(t),
        "localdatetime" => Temporal::LocalDateTime(d.and_time(t)),
        "time" | "datetime" => {
            let selected = map
                .get("datetime")
                .or_else(|| map.get("time"))
                .and_then(|v| temporal_argument(v).ok());
            let inherited = selected
                .and_then(Temporal::offset)
                .map(|offset| match selected {
                    Some(Temporal::DateTime {
                        zone: Some(zone), ..
                    }) if kind == "datetime" => parse_offset(zone, d.and_time(t))
                        .map(|v| v.0)
                        .unwrap_or(offset),
                    _ => offset,
                });
            let zone = map
                .get("timezone")
                .and_then(Value::as_str)
                .map(str::to_owned)
                .unwrap_or_else(|| match selected {
                    Some(Temporal::DateTime {
                        zone: Some(zone), ..
                    }) if kind == "datetime" => zone.clone(),
                    _ => offset_string(inherited.unwrap_or(0)),
                });
            let (offset, zone) = parse_offset(&zone, d.and_time(t))?;
            let local = d
                .and_time(t)
                .checked_add_signed(chrono::Duration::seconds(if map.contains_key("timezone") {
                    i64::from(offset - inherited.unwrap_or(offset))
                } else {
                    0
                }))
                .ok_or_else(|| error("Timezone projection out of range"))?;
            if kind == "time" {
                Temporal::Time {
                    time: local.time(),
                    offset,
                }
            } else {
                Temporal::DateTime {
                    local,
                    offset,
                    zone,
                }
            }
        }
        _ => return Err(error("Unsupported temporal constructor")),
    }))
}
fn truncate(kind: &str, args: &[Value]) -> CypherResult<Value> {
    let unit = args
        .first()
        .and_then(Value::as_str)
        .ok_or_else(|| error("Temporal truncation requires a unit"))?;
    let original = temporal_argument(
        args.get(1)
            .ok_or_else(|| error("Temporal truncation requires a value"))?,
    )?;
    let mut map = IndexMap::new();
    if let Some(mut d) = original.date() {
        d = match unit {
            "millennium" => date(d.year().div_euclid(1000) * 1000, 1, 1)?,
            "century" => date(d.year().div_euclid(100) * 100, 1, 1)?,
            "decade" => date(d.year().div_euclid(10) * 10, 1, 1)?,
            "year" => date(d.year(), 1, 1)?,
            "quarter" => date(d.year(), (d.month() - 1) / 3 * 3 + 1, 1)?,
            "month" => date(d.year(), d.month(), 1)?,
            "week" => d - chrono::Duration::days(i64::from(d.weekday().num_days_from_monday())),
            "weekYear" => {
                let jan4 = date(d.iso_week().year(), 1, 4)?;
                jan4 - chrono::Duration::days(i64::from(jan4.weekday().num_days_from_monday()))
            }
            "day" | "hour" | "minute" | "second" | "millisecond" | "microsecond" => d,
            _ => {
                return Err(error(format!(
                    "Unsupported temporal truncation unit: {unit}"
                )))
            }
        };
        map.insert("date".into(), temporal(Temporal::Date(d)));
    }
    if let Some(t) = original.time() {
        let seconds = t.num_seconds_from_midnight();
        let (seconds, nanos) = match unit {
            "millennium" | "century" | "decade" | "year" | "quarter" | "month" | "week"
            | "weekYear" | "day" => (0, 0),
            "hour" => (seconds / 3600 * 3600, 0),
            "minute" => (seconds / 60 * 60, 0),
            "second" => (seconds, 0),
            "millisecond" => (seconds, t.nanosecond() / 1_000_000 * 1_000_000),
            "microsecond" => (seconds, t.nanosecond() / 1000 * 1000),
            _ => {
                return Err(error(format!(
                    "Unsupported temporal truncation unit: {unit}"
                )))
            }
        };
        map.insert(
            "time".into(),
            temporal(Temporal::LocalTime(
                NaiveTime::from_num_seconds_from_midnight_opt(seconds, nanos)
                    .ok_or_else(|| error("Invalid truncated time"))?,
            )),
        );
    }
    if let Some(offset) = original.offset() {
        map.insert(
            "timezone".into(),
            Value::str(match original {
                Temporal::DateTime {
                    zone: Some(zone), ..
                } => zone.clone(),
                _ => offset_string(offset),
            }),
        );
    }
    if let Some(Value::Map(fields)) = args.get(2) {
        for (key, value) in fields.iter() {
            if key == "nanosecond" && ["millisecond", "microsecond"].contains(&unit) {
                let base = map
                    .get("time")
                    .and_then(|v| temporal_argument(v).ok())
                    .and_then(Temporal::time)
                    .map(|t| i64::from(t.nanosecond()))
                    .unwrap_or(0);
                let extra = if let Value::Int(n) = value {
                    *n
                } else {
                    return Err(error("Nanosecond must be integer"));
                };
                map.insert(
                    key.clone(),
                    Value::Int(
                        base.checked_add(extra)
                            .ok_or_else(|| error("Clock fraction out of range"))?,
                    ),
                );
            } else {
                map.insert(key.clone(), value.clone());
            }
        }
    }
    build(kind, Some(&Value::map(map)))
}
pub fn call(
    name: &str,
    args: &[Value],
    ctx: &dyn crate::context::GraphContext,
) -> Option<CypherResult<Value>> {
    let name = name.to_ascii_lowercase();
    if [
        "duration.between",
        "duration.inmonths",
        "duration.indays",
        "duration.inseconds",
    ]
    .contains(&name.as_str())
    {
        return Some(between(&name, args));
    }
    if name == "duration" {
        return Some(duration::construct(args));
    }
    if name == "datetime.fromepoch" || name == "datetime.fromepochmillis" {
        return Some((|| {
            let first = args
                .first()
                .and_then(|v| {
                    if let Value::Int(n) = v {
                        Some(*n)
                    } else {
                        None
                    }
                })
                .ok_or_else(|| error("Epoch requires integer values"))?;
            let (seconds, nanos) = if name == "datetime.fromepochmillis" {
                (
                    first.div_euclid(1000),
                    u32::try_from(first.rem_euclid(1000) * 1_000_000).unwrap(),
                )
            } else {
                (
                    first,
                    unsigned(
                        args.get(1)
                            .and_then(|v| {
                                if let Value::Int(n) = v {
                                    Some(*n)
                                } else {
                                    None
                                }
                            })
                            .ok_or_else(|| error("Epoch nanoseconds must be integer"))?,
                    )?,
                )
            };
            let dt = chrono::DateTime::from_timestamp(seconds, nanos)
                .ok_or_else(|| error("Epoch out of range"))?;
            Ok(temporal(Temporal::DateTime {
                local: dt.naive_utc(),
                offset: 0,
                zone: None,
            }))
        })());
    }
    for kind in ["date", "localtime", "time", "localdatetime", "datetime"] {
        let clock_kind = ["transaction", "statement", "realtime"]
            .into_iter()
            .find(|clock| name == format!("{kind}.{clock}"));
        let zone_only =
            matches!(args,[Value::Map(map)] if map.len()==1&&map.contains_key("timezone"));
        if clock_kind.is_some() || (name == kind && (args.is_empty() || zone_only)) {
            if args.first().is_some_and(Value::is_null) {
                return Some(Ok(Value::Null));
            }
            let now = if clock_kind == Some("realtime") {
                Utc::now()
            } else {
                ctx.temporal_clock()
            };
            if args.len() > 1
                || args
                    .first()
                    .is_some_and(|value| !matches!(value, Value::Map(_)))
            {
                return Some(Err(error(
                    "Temporal clock accepts at most one timezone map",
                )));
            }
            let zone = args.first().and_then(|value| {
                if let Value::Map(map) = value {
                    map.get("timezone").and_then(Value::as_str)
                } else {
                    None
                }
            });
            let local = if let Some(zone) = zone {
                if let Ok(tz) = zone.parse::<chrono_tz::Tz>() {
                    now.with_timezone(&tz).naive_local()
                } else {
                    let offset = match parse_offset(zone, now.naive_utc()) {
                        Ok((offset, _)) => offset,
                        Err(error) => return Some(Err(error)),
                    };
                    match now
                        .naive_utc()
                        .checked_add_signed(chrono::Duration::seconds(i64::from(offset)))
                    {
                        Some(local) => local,
                        None => return Some(Err(error("Temporal clock outside supported range"))),
                    }
                }
            } else {
                now.naive_utc()
            };
            let mut fields = IndexMap::new();
            fields.insert("date".into(), temporal(Temporal::Date(local.date())));
            fields.insert("time".into(), temporal(Temporal::LocalTime(local.time())));
            if let Some(Value::Map(overrides)) = args.first() {
                fields.extend(overrides.iter().map(|(k, v)| (k.clone(), v.clone())));
            }
            return Some(build(kind, Some(&Value::map(fields))));
        }
        if name == kind {
            return Some(if args.len() > 1 {
                Err(error("Temporal constructor accepts at most one argument"))
            } else {
                build(kind, args.first())
            });
        }
        if name == format!("{kind}.truncate") {
            return Some(truncate(kind, args));
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{engine::Executor, semantics::cypher_equals};
    fn query(query: &str) -> Value {
        let result = Executor::new(vec![], false).execute(query, None).unwrap();
        result.rows[0][&result.columns[0]].clone()
    }
    #[test]
    fn date_calendar_week_ordinal_and_quarter_are_typed() {
        for (input, expected) in [
            ("date({year:1817,week:1})", "1816-12-30"),
            ("date({year:1984,ordinalDay:202})", "1984-07-20"),
            ("date({year:1984,quarter:3,dayOfQuarter:45})", "1984-08-14"),
            ("date('2015W302')", "2015-07-21"),
        ] {
            let value = query(&format!("RETURN {input} AS d"));
            assert!(matches!(value, Value::Temporal(_)));
            assert_eq!(crate::semantics::kotlin_to_string_plain(&value), expected);
            assert_eq!(cypher_equals(&value, &Value::str(expected)), Some(false));
        }
    }
    #[test]
    fn projection_preserves_weekyear_and_component_fields() {
        let value = query("RETURN date({date:date('1816-12-31'),week:2,dayOfWeek:3}) AS d");
        assert_eq!(
            crate::semantics::kotlin_to_string_plain(&value),
            "1817-01-08"
        );
        assert_eq!(
            query("RETURN date('1984-01-01').weekYear AS y").as_f64(),
            Some(1983.0)
        );
    }
    #[test]
    fn clock_precision_and_zone_offsets_are_preserved() {
        let value=query("RETURN time({hour:12,minute:31,second:14,millisecond:123,microsecond:456,nanosecond:789,timezone:'+01:00'}) AS t");
        assert_eq!(
            crate::semantics::kotlin_to_string_plain(&value),
            "12:31:14.123456789+01:00"
        );
        assert!(matches!(
            query("RETURN time('10:00+01:00') < time('09:35Z') AS ok"),
            Value::Bool(true)
        ));
    }
    #[test]
    fn named_zones_use_historical_offsets_and_calendar_truncation() {
        assert_eq!(
            crate::semantics::kotlin_to_string_plain(&query(
                "RETURN datetime('1818-07-21T21:40:32.142[Europe/Stockholm]') AS t"
            )),
            "1818-07-21T21:40:32.142+00:53:28[Europe/Stockholm]"
        );
        assert_eq!(
            crate::semantics::kotlin_to_string_plain(&query(
                "RETURN date.truncate('month',date('2024-02-29')) AS t"
            )),
            "2024-02-01"
        );
    }
    #[test]
    fn partial_date_zone_projection_and_subsecond_truncation_preserve_components() {
        for (input, expected) in [
            ("date('2015-07')", "2015-07-01"),
            ("date({date:date('1984-11-11'),quarter:3})", "1984-08-11"),
            (
                "time({time:time('12:31+01:00'),timezone:'+05:00'})",
                "16:31+05:00",
            ),
            (
                "date.truncate('week',date('1984-10-11'),{dayOfWeek:2})",
                "1984-10-09",
            ),
            (
                "localtime.truncate('millisecond',localtime('12:31:14.645876123'),{nanosecond:2})",
                "12:31:14.645000002",
            ),
        ] {
            assert_eq!(
                crate::semantics::kotlin_to_string_plain(&query(&format!("RETURN {input}"))),
                expected
            );
        }
    }
    #[test]
    fn malformed_dates_and_components_return_errors_without_panicking() {
        for input in [
            "date('2015-W99-1')",
            "date('日期')",
            "date({year:2024,quarter:0})",
            "time({hour:25})",
            "time({nanosecond:9223372036854775807})",
        ] {
            assert!(
                Executor::new(vec![], false)
                    .execute(&format!("RETURN {input}"), None)
                    .is_err(),
                "{input}"
            );
        }
    }
}

fn shift_date(date: NaiveDate, months: i64, days: i64) -> CypherResult<NaiveDate> {
    let months_abs = u32::try_from(months.unsigned_abs())
        .map_err(|_| error("Calendar month shift out of range"))?;
    let shifted = if months < 0 {
        date.checked_sub_months(chrono::Months::new(months_abs))
    } else {
        date.checked_add_months(chrono::Months::new(months_abs))
    }
    .ok_or_else(|| error("Calendar month shift out of range"))?;
    shifted
        .checked_add_signed(
            chrono::Duration::try_days(days)
                .ok_or_else(|| error("Calendar day shift out of range"))?,
        )
        .ok_or_else(|| error("Calendar day shift out of range"))
}
fn shift(value: &Temporal, d: &duration::Duration) -> CypherResult<Temporal> {
    let elapsed = chrono::Duration::try_seconds(d.seconds)
        .and_then(|v| v.checked_add(&chrono::Duration::nanoseconds(i64::from(d.nanos))))
        .ok_or_else(|| error("Temporal arithmetic out of range"))?;
    Ok(match value {
        Temporal::Date(date) => Temporal::Date(shift_date(*date, d.months, d.days)?),
        Temporal::LocalTime(time) => Temporal::LocalTime(time.overflowing_add_signed(elapsed).0),
        Temporal::Time { time, offset } => Temporal::Time {
            time: time.overflowing_add_signed(elapsed).0,
            offset: *offset,
        },
        Temporal::LocalDateTime(dt) => Temporal::LocalDateTime(
            shift_date(dt.date(), d.months, d.days)?
                .and_time(dt.time())
                .checked_add_signed(elapsed)
                .ok_or_else(|| error("Temporal arithmetic out of range"))?,
        ),
        Temporal::DateTime {
            local,
            offset,
            zone,
        } => {
            let calendar = shift_date(local.date(), d.months, d.days)?.and_time(local.time());
            if let Some(zone) = zone {
                let tz: chrono_tz::Tz = zone
                    .parse()
                    .map_err(|_| error("Invalid temporal time zone"))?;
                let value = tz
                    .from_local_datetime(&calendar)
                    .earliest()
                    .ok_or_else(|| error("Temporal arithmetic falls in time zone gap"))?
                    .checked_add_signed(elapsed)
                    .ok_or_else(|| error("Temporal arithmetic out of range"))?;
                Temporal::DateTime {
                    local: value.naive_local(),
                    offset: value.offset().fix().local_minus_utc(),
                    zone: Some(zone.clone()),
                }
            } else {
                Temporal::DateTime {
                    local: calendar
                        .checked_add_signed(elapsed)
                        .ok_or_else(|| error("Temporal arithmetic out of range"))?,
                    offset: *offset,
                    zone: None,
                }
            }
        }
        Temporal::Duration(a) => Temporal::Duration(a.add(d, 1)?),
        Temporal::ExtendedDate(date) => Temporal::ExtendedDate(date.shift(d.months, d.days)?),
        Temporal::ExtendedLocalDateTime(date, time) => {
            let shifted = date.shift(d.months, d.days)?;
            let (time, overflow) = time.overflowing_add_signed(elapsed);
            Temporal::ExtendedLocalDateTime(shifted.shift(0, overflow / 86400)?, time)
        }
    })
}
pub fn binary(op: crate::ast::BinOp, left: &Value, right: &Value) -> Option<CypherResult<Value>> {
    use crate::ast::BinOp;
    match (left, right) {
        (Value::Temporal(a), Value::Temporal(b)) => {
            if let Temporal::Duration(b) = b.as_ref() {
                if op == BinOp::Add {
                    return Some(shift(a, b).map(temporal));
                }
                if op == BinOp::Sub {
                    return Some(b.scale(-1.0).and_then(|b| shift(a, &b)).map(temporal));
                }
            }
            if op == BinOp::Add {
                if let Temporal::Duration(a) = a.as_ref() {
                    return Some(shift(b, a).map(temporal));
                }
            }
            Some(Err(error("Unsupported temporal arithmetic operands")))
        }
        (Value::Temporal(a), b) if b.is_number() => {
            if let Temporal::Duration(a) = a.as_ref() {
                let n = b.as_f64().unwrap();
                match op {
                    BinOp::Mul => return Some(a.scale(n).map(|d| temporal(Temporal::Duration(d)))),
                    BinOp::Div => {
                        return Some(if n == 0.0 {
                            Err(error("Division by zero"))
                        } else {
                            a.scale(1.0 / n).map(|d| temporal(Temporal::Duration(d)))
                        })
                    }
                    _ => {}
                }
            }
            None
        }
        (a, Value::Temporal(b)) if a.is_number() && op == BinOp::Mul => {
            if let Temporal::Duration(b) = b.as_ref() {
                Some(
                    b.scale(a.as_f64().unwrap())
                        .map(|d| temporal(Temporal::Duration(d))),
                )
            } else {
                None
            }
        }
        _ => None,
    }
}

fn offset_at(value: &Temporal, local: NaiveDateTime, inherited: &Temporal) -> CypherResult<i32> {
    let chosen = if value.offset().is_some() {
        value
    } else {
        inherited
    };
    match chosen {
        Temporal::DateTime {
            zone: Some(zone), ..
        } => Ok(parse_offset(zone, local)?.0),
        _ => Ok(chosen.offset().unwrap_or(0)),
    }
}
fn temporal_instant(
    value: &Temporal,
    local: NaiveDateTime,
    inherited: &Temporal,
) -> CypherResult<(i64, u32)> {
    Ok((
        local.and_utc().timestamp() - i64::from(offset_at(value, local, inherited)?),
        local.nanosecond(),
    ))
}
fn between(name: &str, args: &[Value]) -> CypherResult<Value> {
    if args.len() != 2 {
        return Err(error("Temporal difference requires two operands"));
    }
    if args.iter().any(Value::is_null) {
        return Ok(Value::Null);
    }
    let a = temporal_argument(&args[0])?;
    let b = temporal_argument(&args[1])?;
    if matches!(a, Temporal::Duration(_)) || matches!(b, Temporal::Duration(_)) {
        return Err(error("Duration difference requires date/time operands"));
    }
    if matches!(
        a,
        Temporal::ExtendedDate(_) | Temporal::ExtendedLocalDateTime(..)
    ) || matches!(
        b,
        Temporal::ExtendedDate(_) | Temporal::ExtendedLocalDateTime(..)
    ) {
        return between_extended(name, a, b);
    }
    let ad = a.date();
    let bd = b.date();
    let common = ad.or(bd).unwrap_or(date(1970, 1, 1)?);
    let at = a.time().unwrap_or(NaiveTime::MIN);
    let bt = b.time().unwrap_or(NaiveTime::MIN);
    let start = ad.unwrap_or(common).and_time(at);
    let end = bd.unwrap_or(common).and_time(bt);
    let both_dates = ad.is_some() && bd.is_some();
    let mut months = if both_dates {
        i64::from(end.year() - start.year()) * 12 + i64::from(end.month())
            - i64::from(start.month())
    } else {
        0
    };
    let month_anchor = shift_date(start.date(), months, 0)?.and_time(at);
    let anchor_instant = temporal_instant(a, month_anchor, b)?;
    let end_instant = temporal_instant(b, end, a)?;
    if months > 0 && anchor_instant > end_instant {
        months -= 1
    } else if months < 0 && anchor_instant < end_instant {
        months += 1
    }
    if name == "duration.inmonths" {
        return Ok(temporal(Temporal::Duration(duration::Duration {
            months,
            days: 0,
            seconds: 0,
            nanos: 0,
        })));
    }
    let mut calendar_days = if both_dates {
        end.date().signed_duration_since(start.date()).num_days()
    } else {
        0
    };
    if both_dates {
        let candidate = shift_date(start.date(), 0, calendar_days)?.and_time(at);
        let candidate_instant = temporal_instant(a, candidate, b)?;
        if calendar_days > 0 && candidate_instant > end_instant {
            calendar_days -= 1
        } else if calendar_days < 0 && candidate_instant < end_instant {
            calendar_days += 1
        }
    }
    if name == "duration.indays" {
        return Ok(temporal(Temporal::Duration(duration::Duration {
            months: 0,
            days: calendar_days,
            seconds: 0,
            nanos: 0,
        })));
    }
    let (months, days, anchor) = if name == "duration.between" {
        let anchor = shift_date(start.date(), months, 0)?.and_time(at);
        let mut days = if both_dates {
            end.date().signed_duration_since(anchor.date()).num_days()
        } else {
            0
        };
        if days > 0 && bt < at {
            days -= 1
        } else if days < 0 && bt > at {
            days += 1
        }
        (
            months,
            days,
            shift_date(anchor.date(), 0, days)?.and_time(at),
        )
    } else {
        (0, 0, start)
    };
    let offset_delta = i64::from(offset_at(a, anchor, b)? - offset_at(b, end, a)?);
    let elapsed = end
        .signed_duration_since(anchor)
        .checked_add(&chrono::Duration::seconds(offset_delta))
        .ok_or_else(|| error("Duration difference out of range"))?;
    let seconds = elapsed.num_seconds();
    let nanos = (elapsed - chrono::Duration::seconds(seconds))
        .num_nanoseconds()
        .ok_or_else(|| error("Duration fraction out of range"))?;
    Ok(temporal(Temporal::Duration(duration::Duration {
        months,
        days,
        seconds: seconds + nanos.div_euclid(1_000_000_000),
        nanos: nanos.rem_euclid(1_000_000_000) as i32,
    })))
}

fn between_extended(name: &str, a: &Temporal, b: &Temporal) -> CypherResult<Value> {
    let ad = a
        .civil_date()
        .ok_or_else(|| error("Extended temporal requires calendar dates"))?;
    let bd = b
        .civil_date()
        .ok_or_else(|| error("Extended temporal requires calendar dates"))?;
    let at = a.time().unwrap_or(NaiveTime::MIN);
    let bt = b.time().unwrap_or(NaiveTime::MIN);
    let mut months = i64::from(bd.year - ad.year) * 12 + i64::from(bd.month) - i64::from(ad.month);
    if months > 0 && (bd.day, bt) < (ad.day, at) {
        months -= 1
    } else if months < 0 && (bd.day, bt) > (ad.day, at) {
        months += 1
    }
    if name == "duration.inmonths" {
        return Ok(temporal(Temporal::Duration(duration::Duration {
            months,
            days: 0,
            seconds: 0,
            nanos: 0,
        })));
    }
    let mut days = bd.epoch_day() - ad.epoch_day();
    if days > 0 && bt < at {
        days -= 1
    } else if days < 0 && bt > at {
        days += 1
    }
    if name == "duration.indays" {
        return Ok(temporal(Temporal::Duration(duration::Duration {
            months: 0,
            days,
            seconds: 0,
            nanos: 0,
        })));
    }
    let (months, days, anchor) = if name == "duration.between" {
        let anchor = ad.shift(months, 0)?;
        let mut days = bd.epoch_day() - anchor.epoch_day();
        if days > 0 && bt < at {
            days -= 1
        } else if days < 0 && bt > at {
            days += 1
        }
        (months, days, anchor.shift(0, days)?)
    } else {
        (0, 0, ad)
    };
    let seconds = (bd.epoch_day() - anchor.epoch_day()) * 86400
        + i64::from(bt.num_seconds_from_midnight())
        - i64::from(at.num_seconds_from_midnight());
    let nanos = i64::from(bt.nanosecond()) - i64::from(at.nanosecond());
    Ok(temporal(Temporal::Duration(duration::Duration {
        months,
        days,
        seconds: seconds + nanos.div_euclid(1_000_000_000),
        nanos: nanos.rem_euclid(1_000_000_000) as i32,
    })))
}

#[cfg(test)]
mod duration_tests {
    use super::*;
    use crate::engine::Executor;
    fn result(query: &str) -> Value {
        let result = Executor::new(vec![], false).execute(query, None).unwrap();
        result.rows[0][&result.columns[0]].clone()
    }
    fn text(expression: &str) -> String {
        crate::semantics::kotlin_to_string_plain(&result(&format!("RETURN {expression} AS d")))
    }
    #[test]
    fn duration_components_retain_calendar_units_and_subsecond_precision() {
        assert_eq!(text("duration({months:0.75})"), "P22DT19H51M49.5S");
        assert_eq!(
            text("duration({days:14,seconds:70,nanoseconds:1})"),
            "P14DT1M10.000000001S"
        );
        assert_eq!(
            text("duration('P2012-02-02T14:37:21.545')"),
            "P2012Y2M2DT14H37M21.545S"
        );
        assert!(matches!(
            result("RETURN duration('P1D') = duration('PT24H') AS same"),
            Value::Bool(false)
        ));
        assert!(matches!(
            result("RETURN duration({seconds:70}) = duration({minutes:1,seconds:10}) AS same"),
            Value::Bool(true)
        ));
    }
    #[test]
    fn calendar_arithmetic_clamps_month_end_and_clock_arithmetic_wraps() {
        assert_eq!(text("date('2023-01-31') + duration('P1M')"), "2023-02-28");
        assert_eq!(text("localtime('12:31:14.000000001') + duration({hours:16,minutes:12,seconds:70,nanoseconds:2})"),"04:44:24.000000003");
        assert_eq!(text("duration('P1M') * 0.5"), "P15DT5H14M33S");
        assert_eq!(
            text("duration('PT0.001S') - duration('PT0.002S')"),
            "PT-0.001S"
        );
    }
    #[test]
    fn duration_differences_distinguish_calendar_and_elapsed_time_across_dst() {
        assert_eq!(text("duration.between(datetime('2017-10-28T23:00+02:00[Europe/Stockholm]'), datetime('2017-10-29T04:00+01:00[Europe/Stockholm]'))"),"PT6H");
        assert_eq!(text("duration.inSeconds(datetime({year:2017,month:10,day:29,hour:0,timezone:'Europe/Stockholm'}),localdatetime({year:2017,month:10,day:29,hour:4}))"),"PT5H");
        assert_eq!(
            text("duration.between(date('1984-10-11'),date('2015-06-24'))"),
            "P30Y8M13D"
        );
        assert_eq!(text("duration.inSeconds(localdatetime('2014-07-21T21:40:36.143'),localdatetime('2014-07-21T21:40:36.142'))"),"PT-0.001S");
    }
    #[test]
    fn proleptic_gregorian_extremes_use_integer_calendar_arithmetic() {
        assert_eq!(
            text("duration.between(date('-999999999-01-01'),date('+999999999-12-31'))"),
            "P1999999998Y11M30D"
        );
        assert_eq!(text("duration.inSeconds(localdatetime('-999999999-01-01'),localdatetime('+999999999-12-31T23:59:59'))"),"PT17531639991215H59M59S");
        let epoch = civil::CivilDate::new(1970, 1, 1).unwrap();
        assert_eq!(epoch.epoch_day(), 0);
        for d in [
            civil::CivilDate::new(-999999999, 1, 1).unwrap(),
            civil::CivilDate::new(0, 2, 29).unwrap(),
            civil::CivilDate::new(999999999, 12, 31).unwrap(),
        ] {
            assert_eq!(civil::CivilDate::from_epoch_day(d.epoch_day()).unwrap(), d);
        }
    }
    #[test]
    fn query_clock_is_stable_and_all_clock_modes_propagate_null() {
        assert_eq!(
            text("duration.inSeconds(localdatetime(),localdatetime())"),
            "PT0S"
        );
        assert_eq!(
            text("duration.inSeconds(time.transaction(),time.statement())"),
            "PT0S"
        );
        assert!(matches!(result("RETURN datetime.transaction({timezone:'+01:00'}).epochSeconds = datetime.transaction({timezone:'+02:00'}).epochSeconds AS ok"),Value::Bool(true)));
        for kind in ["date", "time", "localtime", "datetime", "localdatetime"] {
            for mode in ["transaction", "statement", "realtime"] {
                assert!(result(&format!("RETURN {kind}.{mode}(null) AS t")).is_null());
            }
        }
    }
}
