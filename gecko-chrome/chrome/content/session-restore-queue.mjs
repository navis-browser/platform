// SPDX-License-Identifier: MPL-2.0

// Platform schedules first loads; Runtime remains the owner of every Session
// and its actual navigation state. A user-selected tab never waits in line.
export class SessionRestoreQueue {
  #queue = [];
  #running = new Set();
  #selected = null;
  #timer = null;
  #closed = false;
  #schedule;
  #cancel;
  #onError;
  #maxConcurrent;

  constructor({ hardwareConcurrency = 1,
    schedule = task => setTimeout(task, 0), cancel = clearTimeout,
    onError = error => console.error("Navis could not restore a tab", error) } = {}) {
    this.#schedule = schedule;
    this.#cancel = cancel;
    this.#onError = onError;
    // One automatic load per reported logical processor. Missing host
    // information retains the single-load fallback.
    this.#maxConcurrent = Number.isSafeInteger(hardwareConcurrency) && hardwareConcurrency > 0
      ? hardwareConcurrency : 1;
  }

  enqueue(sessions) {
    if (this.#closed) return;
    this.#queue.push(...sessions);
  }

  pendingURL(session) {
    return session.state.pendingURL ?? null;
  }

  activate(session) {
    this.#selected = session;
    return this.load(session);
  }

  load(session) {
    if (this.#closed) return false;
    const url = this.pendingURL(session);
    if (url === null || this.#running.has(session)) {
      this.#advance();
      return false;
    }
    this.#queue = this.#queue.filter(candidate => candidate !== session);
    if (session.state.closed) {
      this.remove(session);
      return false;
    }
    this.#running.add(session);
    try {
      session.open(url);
    } catch (error) {
      // Retain an address rejected before navigation so saving or selecting
      // this tab cannot silently replace it with about:blank. Do not retry it
      // automatically in a tight loop.
      this.#running.delete(session);
      this.#onError(error);
    }
    this.update(session);
    return true;
  }

  update(session) {
    if (this.#closed) return;
    if (session.state.closed) {
      this.remove(session);
      return;
    }
    if ((!session.state.loading && session.state.navigationId) || session.state.crashed) {
      this.#running.delete(session);
    }
    this.#advance();
  }

  remove(session) {
    this.#queue = this.#queue.filter(candidate => candidate !== session);
    this.#running.delete(session);
    if (this.#selected === session) this.#selected = null;
    this.#advance();
  }

  close() {
    this.#closed = true;
    if (this.#timer !== null) this.#cancel(this.#timer);
    this.#timer = null;
    this.#queue = [];
    this.#running.clear();
    // The final window snapshot still needs the unopened URLs.
  }

  #advance() {
    if (this.#closed || this.#timer !== null || !this.#hasCapacity() || !this.#queue.length) return;
    this.#timer = this.#schedule(() => {
      this.#timer = null;
      if (this.#closed || !this.#hasCapacity()) return;
      while (this.#queue.length) {
        const session = this.#queue.shift();
        if (this.pendingURL(session) !== null && !session.state.closed) {
          this.load(session);
          break;
        }
      }
    });
  }

  #hasCapacity() {
    // The foreground may be an ordinary navigation outside this queue. Count
    // it once, and let explicit user selections bypass the automatic ceiling.
    const foreground = this.#selectedLoading() && !this.#running.has(this.#selected) ? 1 : 0;
    return this.#running.size + foreground < this.#maxConcurrent;
  }

  #selectedLoading() {
    return this.#selected?.state.loading && !this.#selected.state.crashed;
  }
}
