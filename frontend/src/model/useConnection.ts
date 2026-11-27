import { shallowRef } from 'vue'
import { browserStorage, ConnectionStore, validModel } from './connection'
import { DirectTransport, ModelError } from './direct'
import { modelMessage } from './copy'

/** 模型连接：保存的配置、直连传输层，以及防止 Key 混进 Prompt 和正文。 */
export function useConnection() {
  const store = new ConnectionStore(browserStorage('localStorage'), browserStorage('sessionStorage'), location.origin)
  store.selectAccount('local-practice')
  const connection = shallowRef(store.profile)
  const transport = new DirectTransport(location.origin)

  function syncConnection() {
    connection.value = store.profile
  }

  function guardKey(text: string) {
    const key = store.getKey(store.profile?.base)
    if (key && text.includes(key)) throw new Error(modelMessage(new ModelError('CREDENTIAL')))
  }

  const ready = () => !!connection.value && validModel(connection.value.model)

  return { store, connection, transport, syncConnection, guardKey, ready }
}
