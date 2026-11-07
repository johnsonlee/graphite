//! Proleptic Gregorian dates across the ISO temporal year range, including years
//! outside chrono's bounded NaiveDate representation. All arithmetic is integral.
use super::{error, CypherResult};
use std::fmt;
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct CivilDate {
    pub year: i32,
    pub month: u32,
    pub day: u32,
}
impl CivilDate {
    pub fn new(year: i32, month: u32, day: u32) -> CypherResult<Self> {
        if !(-999_999_999..=999_999_999).contains(&year) || !(1..=12).contains(&month) {
            return Err(error("Calendar date out of range"));
        }
        let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
        let length = match month {
            2 => {
                if leap {
                    29
                } else {
                    28
                }
            }
            4 | 6 | 9 | 11 => 30,
            _ => 31,
        };
        if day == 0 || day > length {
            return Err(error("Invalid calendar day"));
        }
        Ok(Self { year, month, day })
    }
    pub fn parse(text: &str) -> CypherResult<Self> {
        let (year_month, day) = text
            .rsplit_once('-')
            .ok_or_else(|| error("Invalid Gregorian date"))?;
        let (year, month) = year_month
            .rsplit_once('-')
            .ok_or_else(|| error("Invalid Gregorian date"))?;
        Self::new(
            year.parse().map_err(|_| error("Invalid Gregorian year"))?,
            month
                .parse()
                .map_err(|_| error("Invalid Gregorian month"))?,
            day.parse().map_err(|_| error("Invalid Gregorian day"))?,
        )
    }
    pub fn epoch_day(self) -> i64 {
        let year = i64::from(self.year) - i64::from(self.month <= 2);
        let era = year.div_euclid(400);
        let year_of_era = year - era * 400;
        let adjusted_month = i64::from(self.month) + if self.month > 2 { -3 } else { 9 };
        let day_of_year = (153 * adjusted_month + 2) / 5 + i64::from(self.day) - 1;
        let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
        era * 146097 + day_of_era - 719468
    }
    pub fn from_epoch_day(day: i64) -> CypherResult<Self> {
        let z = day
            .checked_add(719468)
            .ok_or_else(|| error("Calendar day out of range"))?;
        let era = z.div_euclid(146097);
        let doe = z - era * 146097;
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        let mut year = yoe + era * 400;
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        let mp = (5 * doy + 2) / 153;
        let day = doy - (153 * mp + 2) / 5 + 1;
        let month = mp + if mp < 10 { 3 } else { -9 };
        year += i64::from(month <= 2);
        Self::new(
            i32::try_from(year).map_err(|_| error("Calendar year out of range"))?,
            month as u32,
            day as u32,
        )
    }
    pub fn shift(self, months: i64, days: i64) -> CypherResult<Self> {
        let absolute = i64::from(self.year)
            .checked_mul(12)
            .and_then(|year| year.checked_add(i64::from(self.month) - 1))
            .and_then(|month| month.checked_add(months))
            .ok_or_else(|| error("Calendar month overflow"))?;
        let year =
            i32::try_from(absolute.div_euclid(12)).map_err(|_| error("Calendar year overflow"))?;
        let month = (absolute.rem_euclid(12) + 1) as u32;
        let mut day = self.day;
        let shifted = loop {
            match Self::new(year, month, day) {
                Ok(d) => break d,
                Err(_) if day > 28 => day -= 1,
                Err(e) => return Err(e),
            }
        };
        Self::from_epoch_day(
            shifted
                .epoch_day()
                .checked_add(days)
                .ok_or_else(|| error("Calendar day overflow"))?,
        )
    }
}
impl fmt::Display for CivilDate {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.year < 0 {
            write!(
                f,
                "-{:04}-{:02}-{:02}",
                self.year.unsigned_abs(),
                self.month,
                self.day
            )
        } else if self.year > 9999 {
            write!(f, "+{}-{:02}-{:02}", self.year, self.month, self.day)
        } else {
            write!(f, "{:04}-{:02}-{:02}", self.year, self.month, self.day)
        }
    }
}
