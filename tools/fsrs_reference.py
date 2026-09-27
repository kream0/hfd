# Generates core/src/test/resources/fsrs_reference.json with the reference FSRS
# implementation (pip install fsrs==6.3.2): FsrsTest replays the same reviews in Kotlin.
import json
from datetime import datetime, timezone, timedelta
from fsrs import Scheduler, Card, Rating, State
sch = Scheduler(enable_fuzzing=False)
R = {'again': Rating.Again, 'hard': Rating.Hard, 'good': Rating.Good, 'easy': Rating.Easy}
t0 = datetime(2026, 1, 1, 8, 0, tzinfo=timezone.utc)
def ms(dt): return int(dt.timestamp() * 1000)
scenarios = {
 'A': [('good', 'due'), ('good', 'due'), ('good', 'due'), ('good', 'due'), ('again', 'due'), ('good', 'due'), ('hard', 'due'), ('easy', 'due'), ('good', 'due')],
 'B': [('again', 'due'), ('hard', 'due'), ('easy', 'due'), ('good', '+3d'), ('good', '-1d'), ('again', 'due'), ('again', 'due'), ('good', 'due'), ('good', 'due'), ('hard', '+40d')],
 'C': [('easy', 'due'), ('good', '+2h'), ('hard', 'due'), ('again', '+1h'), ('good', '+5m'), ('easy', 'due')],
 'D': [('hard', 'due'), ('hard', 'due'), ('hard', 'due'), ('good', 'due'), ('good', 'due'), ('good', 'due'), ('good', 'due'), ('good', 'due')],
}
out = {}
for name, steps in scenarios.items():
    card = Card()
    now = t0
    rows = []
    for rating, when in steps:
        if card.last_review is not None:
            if when == 'due': now = card.due
            elif when.startswith('+') or when.startswith('-'):
                sign = 1 if when[0] == '+' else -1
                n = int(when[1:-1]); unit = when[-1]
                delta = {'d': timedelta(days=n), 'h': timedelta(hours=n), 'm': timedelta(minutes=n)}[unit]
                now = card.due + sign * delta if unit == 'd' else card.last_review + delta
        card, _ = sch.review_card(card, R[rating], review_datetime=now)
        rows.append({'rating': rating, 'at': ms(now), 'state': card.state.name.lower(), 'step': card.step,
                     'stability': card.stability, 'difficulty': card.difficulty, 'due': ms(card.due)})
    out[name] = rows
# retrievability samples
card = Card(); card, _ = sch.review_card(card, Rating.Good, review_datetime=t0)
card, _ = sch.review_card(card, Rating.Good, review_datetime=card.due)
ret = []
for days in (0, 1, 2, 5, 10, 30, 100):
    ret.append({'at': ms(card.last_review + timedelta(days=days, hours=1)), 'r': sch.get_card_retrievability(card, card.last_review + timedelta(days=days, hours=1))})
out['retrievability'] = {'stability': card.stability, 'difficulty': card.difficulty, 'lastReview': ms(card.last_review), 'due': ms(card.due), 'samples': ret}
print(json.dumps(out, indent=1))
