package org.quill

import org.quill.lang.Value

interface InkContext {
    fun log(message: String)
    fun print(message: String)

    fun registerEventHandler(
        eventName: String,
        handlerFunc: Value.Function,
        eventParamName: String,
        dataParamNames: List<String>
    )

    fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean
}