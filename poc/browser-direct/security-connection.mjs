import {endpoint, run} from '/transport.mjs';

// This fixture models a single active connection per account for the PoC.
export function connection(initialAccount) {
  let account;
  let base;
  let key;
  let pending;
  const configSlot = () => 'poc02:config:' + account;
  const keySlot = () => 'poc02:key:' + account;

  function clear() {
    key = '';
    sessionStorage.removeItem(keySlot());
  }

  function load(next) {
    account = next;
    base = localStorage.getItem(configSlot()) ?? '';
    const remembered = JSON.parse(sessionStorage.getItem(keySlot()) ?? 'null');
    key = remembered?.base === base ? remembered?.key ?? '' : '';
    if (!key) sessionStorage.removeItem(keySlot());
    pending = undefined;
  }

  function propose(address) {
    const normalized = endpoint(address, 'models').slice(0, -'/models'.length);
    if (normalized === base) {
      pending = undefined;
      return;
    }
    clear();
    pending = normalized;
  }

  load(initialAccount);
  return {
    propose,
    confirm() {
      if (!pending) return;
      base = pending;
      pending = undefined;
      localStorage.setItem(configSlot(), base);
    },
    setKey(value, remember = false) {
      if (pending || !base) throw new Error('confirm_target_first');
      clear();
      key = value;
      if (remember && key) sessionStorage.setItem(keySlot(), JSON.stringify({base, key}));
    },
    clear,
    remove() {
      clear();
      localStorage.removeItem(configSlot());
      base = '';
      pending = undefined;
    },
    switchAccount(next) {
      clear();
      load(next);
    },
    logout() {
      clear();
      base = '';
      pending = undefined;
    },
    request() {
      if (pending || !base) return Promise.resolve({ok: false, code: 'target_not_confirmed'});
      return run({base, key, model: 'mock-model'});
    },
  };
}
