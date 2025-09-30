package com.agentclientprotocol.util

import kotlinx.coroutines.Dispatchers

public actual val DispatcherIO: kotlinx.coroutines.CoroutineDispatcher
    get() = Dispatchers.Default