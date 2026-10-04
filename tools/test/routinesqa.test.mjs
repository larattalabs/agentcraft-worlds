// node --test tools/test   (or: npm test --prefix tools)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bedsOf, checkMorning, checkNight } from '../lib/routinesqa.mjs';

const beds = {
  workshop: [
    { name: 'bed', head: '10 64 20', facing: 'north', occupied: false, approach: '9.5 64.0 20.5', agent: 'kit' },
    { name: 'bed_2', head: '12 64 20', facing: 'north', occupied: false, approach: '13.5 64.0 20.5', agent: null },
  ],
};
const night = (agents) => ({ night: true, timeOfDay: 13200, settings: { night: true }, beds, agents });
const kitInBed = { id: 'kit', routine: 'resting', layout: 'workshop', lying: 'bed', plate: 'resting', target: 'bed@rest', walking: false, pos: '10.50 64.69 20.50' };

test('bedsOf finds the foot behind the head', () => {
  const b = bedsOf({ beds }).get('workshop|bed');
  assert.deepEqual(b.head, [10, 64, 20]);
  assert.deepEqual(b.foot, [10, 64, 21]);
});

test('night passes when an agent rests in its bed', () => {
  const r = checkNight(night([kitInBed, { id: 'ash', routine: 'work', lying: null, plate: 'typing', pos: '0 64 0' }]));
  assert.equal(r.ok, true, r.reasons.join('; '));
  assert.deepEqual(r.lying, ['kit']);
});

test('night fails with the reason', () => {
  assert.match(checkNight(night([{ ...kitInBed, lying: null }])).reasons.join(), /nobody lies in a bed yet; resting: kit->bed@rest/);
  assert.match(checkNight(night([{ ...kitInBed, plate: 'idle' }])).reasons.join(), /plate 'idle'/);
  assert.match(checkNight(night([{ ...kitInBed, routine: 'work' }])).reasons.join(), /routine is work/);
  assert.match(checkNight(night([{ ...kitInBed, pos: '9.5 64 20.5' }])).reasons.join(), /not on bed's head/);
  assert.match(checkNight(night([kitInBed, { ...kitInBed, id: 'ash' }])).reasons.join(), /share workshop\|bed/);
  assert.match(checkNight({ ...night([kitInBed]), night: false }).reasons.join(), /not night yet/);
  assert.match(checkNight({ ...night([]), beds: {} }).reasons.join(), /no beds found/);
});

test('morning passes when the sleepers stood up beside their beds', () => {
  const s = { night: false, timeOfDay: 23050, beds, agents: [{ ...kitInBed, routine: 'work', lying: null, plate: 'idle', pos: '9.50 64.00 20.50' }] };
  const r = checkMorning(s, ['kit']);
  assert.equal(r.ok, true, r.reasons.join('; '));
});

test('morning fails when someone still lies, rests or stood up inside the bed', () => {
  const base = { night: false, timeOfDay: 23050, beds };
  assert.match(checkMorning({ ...base, agents: [kitInBed] }, ['kit']).reasons.join(), /still lies in bed/);
  const inFoot = { ...kitInBed, routine: 'work', lying: null, plate: 'idle', pos: '10.5 64.0 21.5' };
  assert.match(checkMorning({ ...base, agents: [inFoot] }, ['kit']).reasons.join(), /got up inside workshop\|bed/);
  assert.equal(checkMorning({ ...base, agents: [inFoot] }, []).ok, true, 'only the sleepers are checked for the bed cells');
  assert.match(checkMorning({ ...base, night: true, agents: [] }).reasons.join(), /still night/);
});
