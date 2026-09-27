// Generates core/src/test/resources/prayer_reference.json with adhan-js 4 (npm i adhan@4),
// the reference PrayerTimesTest compares HFD's calculator against.
const a = require('adhan');
const cities = {
  paris: [48.8566, 2.3522, 'Europe/Paris'],
  lyon: [45.764, 4.8357, 'Europe/Paris'],
  makkah: [21.4225, 39.8262, 'Asia/Riyadh'],
  casablanca: [33.5731, -7.5898, 'Africa/Casablanca'],
  montreal: [45.5017, -73.5673, 'America/Toronto'],
  oslo: [59.9139, 10.7522, 'Europe/Oslo'],
};
const methods = {
  mwl: () => a.CalculationMethod.MuslimWorldLeague(),
  isna: () => a.CalculationMethod.NorthAmerica(),
  egypt: () => a.CalculationMethod.Egyptian(),
  makkah: () => a.CalculationMethod.UmmAlQura(),
  uoif: () => { const p = a.CalculationMethod.Other(); p.fajrAngle = 12; p.ishaAngle = 12; return p; },
};
const dates = ['2026-01-15', '2026-03-21', '2026-06-21', '2026-09-27', '2026-12-21'];
const out = [];
for (const [city, [lat, lng, tz]] of Object.entries(cities)) for (const [m, mk] of Object.entries(methods)) for (const d of dates) {
  const p = mk(); p.highLatitudeRule = a.HighLatitudeRule.MiddleOfTheNight; p.madhab = a.Madhab.Shafi;
  p.methodAdjustments = { fajr: 0, sunrise: 0, dhuhr: 0, asr: 0, maghrib: 0, isha: 0 };
  const [Y, M, D] = d.split('-').map(Number);
  const t = new a.PrayerTimes(new a.Coordinates(lat, lng), new Date(Date.UTC(Y, M - 1, D, 12)), p);
  const fmt = x => x.toISOString();
  out.push({ city, lat, lng, tz, method: m, date: d, fajr: fmt(t.fajr), sunrise: fmt(t.sunrise), dhuhr: fmt(t.dhuhr), asr: fmt(t.asr), maghrib: fmt(t.maghrib), isha: fmt(t.isha) });
}
console.log(JSON.stringify(out));
