// SPDX-License-Identifier: MPL-2.0
import assert from "node:assert/strict";
import { SessionRestoreQueue } from "../gecko-chrome/chrome/content/session-restore-queue.mjs";

function fixture({ hardwareConcurrency = 1, count = 4 } = {}) {
  const tasks = new Map(), loads = [], errors = [];
  let id = 0, queue;
  const names = ["first", "selected", "third", "fourth", "fifth", "sixth", "seventh", "eighth"];
  const sessions = Array.from({ length: count }, (_, index) => names[index] ?? `tab-${index + 1}`).map(name => ({
    name, state: { navigationId: 0, url: "about:blank", pendingURL: `https://${name}.invalid/`, loading: false, closed: false, crashed: false },
    open(url) {
      queue.update(this); // Native host setup may notify before navigation.
      loads.push(name);
      this.state = { ...this.state, url, pendingURL: null, navigationId: this.state.navigationId + 1, loading: true };
      queue.update(this);
    },
  }));
  queue = new SessionRestoreQueue({
    hardwareConcurrency,
    schedule(task) { tasks.set(++id, task); return id; },
    cancel(handle) { tasks.delete(handle); }, onError(error) { errors.push(error); },
  });
  queue.enqueue(sessions);
  return { queue, sessions, loads, errors,
    drain() {
      for (let turns = 0; tasks.size; ++turns) {
        assert.ok(turns <= sessions.length, "Restoration must not schedule a busy loop");
        this.tick();
      }
    },
    tick() {
      const pending = [...tasks.values()]; tasks.clear();
      for (const task of pending) task();
    },
    finish(session, patch = {}) {
      session.state = { ...session.state, loading: false, ...patch }; queue.update(session);
    },
  };
}
// Each logical processor supplies a slot, including odd and large host counts.
for (const [hardwareConcurrency, ceiling] of [
  [1, 1], [2, 2], [3, 3], [4, 4], [5, 5], [6, 6], [7, 7], [8, 8], [16, 16], [128, 128],
  [0, 1], [-1, 1], [NaN, 1], [Infinity, 1], ["8", 1], [4.5, 1], [null, 1],
  [Number.MAX_SAFE_INTEGER + 1, 1],
]) {
  const f = fixture({ hardwareConcurrency, count: ceiling + 1 });
  const selected = f.sessions[1];
  f.queue.activate(selected);
  assert.deepEqual(f.loads, ["selected"], "Foreground starts before any automatic work");
  f.drain();
  assert.equal(f.loads.length, ceiling, `Processor count ${hardwareConcurrency} sets ceiling ${ceiling}`);
  f.finish(selected); f.drain();
  assert.equal(f.loads.length, ceiling + 1, "Completion refills only the newly available slot");
}
{
  const f = fixture({ hardwareConcurrency: 4, count: 8 });
  const [first, selected, third, fourth, fifth, sixth] = f.sessions;
  f.queue.activate(selected); f.drain();
  assert.deepEqual(f.loads, ["selected", "first", "third", "fourth"]);
  f.queue.activate(sixth);
  assert.equal(f.loads.at(-1), "sixth", "User selection bypasses a full automatic queue");
  f.queue.activate(sixth); f.drain();
  assert.equal(f.loads.length, 5, "Repeated selection does not reload or start more automatic work");
  f.finish(first); f.drain();
  assert.equal(f.loads.length, 5, "Four remaining loads still occupy all automatic slots");
  f.finish(third); f.drain();
  assert.equal(f.loads.at(-1), "fifth", "Refill does not wait for the foreground to finish when capacity exists");
  assert.ok(sixth.state.loading);
  f.queue.close();
}
{
  const f = fixture({ hardwareConcurrency: 2, count: 8 });
  const [first, selected, third] = f.sessions;
  f.queue.activate(selected); f.drain();
  f.finish(selected);
  selected.state.loading = true; // A new foreground navigation outside restoration.
  f.queue.update(selected); f.drain();
  assert.deepEqual(f.loads, ["selected", "first"], "Ordinary foreground loading also occupies one slot");
  f.queue.activate(selected); f.drain();
  assert.equal(f.loads.length, 2, "Selecting an already opened tab cannot bypass slot accounting");
  f.finish(first); f.drain();
  assert.equal(f.loads.at(-1), "third", "The ordinary foreground is counted once");
  f.finish(selected); f.drain();
  assert.equal(f.loads.at(-1), "fourth", "Foreground completion frees its slot");
  f.queue.close();
}
{
  const f = fixture({ hardwareConcurrency: 4, count: 8 });
  f.queue.activate(f.sessions[1]);
  // Fill the slots through user actions before the scheduled callback runs.
  f.queue.activate(f.sessions[0]); f.queue.activate(f.sessions[2]); f.queue.activate(f.sessions[3]);
  f.drain();
  assert.deepEqual(f.loads, ["selected", "first", "third", "fourth"], "Scheduled work rechecks capacity");
  f.queue.close();
}
for (const result of [{}, { failureCode: 42 }, { crashed: true }, { crashed: true, loading: true }]) {
  const f = fixture({ hardwareConcurrency: 4, count: 8 });
  f.queue.activate(f.sessions[1]); f.drain();
  f.finish(f.sessions[0], result); f.drain();
  assert.equal(f.loads.at(-1), "fifth", "Parallel completion, stop, failure and crash each release a slot");
  assert.equal(f.loads.length, 5);
  f.queue.close();
}
{
  const f = fixture({ hardwareConcurrency: 4, count: 8 });
  const [first, selected, third] = f.sessions;
  f.queue.activate(selected); f.drain();
  first.state.closed = true; f.queue.remove(first); f.drain();
  assert.equal(f.loads.at(-1), "fifth", "Closing an in-flight tab releases its slot");
  f.finish(third); f.queue.close(); f.drain();
  assert.equal(f.loads.length, 5, "Closing the window cancels a pending refill");
  assert.equal(f.queue.pendingURL(f.sessions[5]), "https://sixth.invalid/", "Unstarted parallel work retains its saved URL");
  assert.equal(f.queue.activate(f.sessions[5]), false, "A closed queue never restarts");
}
{
  const f = fixture({ hardwareConcurrency: 4, count: 8 });
  const [first, selected] = f.sessions;
  first.open = () => { throw new Error("Rejected before navigation"); };
  f.queue.activate(selected); f.drain();
  assert.equal(f.errors.length, 1);
  assert.deepEqual(f.loads, ["selected", "third", "fourth", "fifth"], "A rejected open cannot strand a slot or spin");
  assert.equal(f.queue.pendingURL(first), "https://first.invalid/");
  f.queue.close();
}
{
  const f = fixture(), [first, selected, third, fourth] = f.sessions;
  f.queue.activate(selected);
  f.tick(); assert.deepEqual(f.loads, ["selected"], "Initial foreground must load alone");
  f.finish(selected); assert.deepEqual(f.loads, ["selected"], "Yield a task before background loading");
  f.tick(); assert.deepEqual(f.loads, ["selected", "first"]);
  f.tick(); assert.equal(f.loads.length, 2, "Only one automatic background load at a time");
  f.finish(first); f.tick(); f.finish(third); f.tick(); f.finish(fourth); f.tick();
  assert.deepEqual(f.loads, ["selected", "first", "third", "fourth"]);
}
{
  const f = fixture(), [first, selected, third, fourth] = f.sessions;
  f.queue.activate(selected); f.finish(selected); f.tick();
  f.queue.activate(fourth);
  assert.deepEqual(f.loads, ["selected", "first", "fourth"], "Selecting a pending tab bypasses an in-flight background load");
  f.queue.activate(fourth); assert.equal(f.loads.length, 3, "Selecting twice must not open twice");
  f.finish(first); f.tick(); assert.equal(f.loads.length, 3, "Pause the queue until the selected load finishes");
  f.finish(fourth); f.tick(); assert.deepEqual(f.loads, ["selected", "first", "fourth", "third"]);
  f.finish(third); f.tick(); assert.equal(f.loads.length, 4);
}
for (const result of [{}, { failureCode: 42 }, { crashed: true }, { crashed: true, loading: true }]) {
  const f = fixture(), [first, selected] = f.sessions;
  f.queue.activate(selected); f.finish(selected, result); f.tick();
  assert.deepEqual(f.loads, ["selected", "first"], "Completion, stop, load failure and crash release the queue");
  f.finish(first); f.tick(); assert.equal(f.loads.at(-1), "third");
}
{
  const f = fixture(), [first, selected, third] = f.sessions;
  f.queue.activate(selected);
  first.state = { ...first.state, url: "https://extension.invalid/", pendingURL: null, navigationId: 1 };
  f.queue.update(first); f.finish(selected); f.tick();
  assert.deepEqual(f.loads, ["selected", "third"], "An extension navigation replaces queued restoration intent");
  f.finish(third); f.tick();
  assert.equal(f.loads.at(-1), "fourth");
}
{
  const f = fixture(), [first, selected] = f.sessions;
  f.queue.activate(selected); f.finish(selected);
  first.state.closed = true; f.queue.remove(first); f.tick();
  assert.deepEqual(f.loads, ["selected", "third"], "A closed pending tab must never start");
}
{
  const f = fixture(), [first, selected] = f.sessions;
  f.queue.activate(selected); f.finish(selected); f.queue.close(); f.tick();
  assert.deepEqual(f.loads, ["selected"], "Window shutdown cancels scheduled background work");
  assert.equal(f.queue.pendingURL(first), "https://first.invalid/", "Final persistence still has every unopened URL");
}
{
  const f = fixture(), [first, selected] = f.sessions;
  first.open = () => { throw new Error("Rejected before navigation"); };
  f.queue.activate(selected); f.finish(selected); f.tick(); f.tick();
  assert.equal(f.errors.length, 1); assert.equal(f.loads.at(-1), "third");
  assert.equal(f.queue.pendingURL(first), "https://first.invalid/", "Rejected opens do not erase saved addresses");
}
{
  const f = fixture(), [first, selected] = f.sessions;
  f.queue.activate(selected); f.finish(selected); f.tick();
  first.state.loading = true;
  f.queue.activate(first); f.finish(first); f.tick();
  assert.deepEqual(f.loads, ["selected", "first", "third"]);
}
console.log("CPU-based restoration bounds, selected-first task ordering, foreground priority, slot refill, failure/stop/close and pending-URL checks passed");
