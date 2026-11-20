package com.shipyard.deepchat.data

/**
 * 消息分支：会话里存全部节点，用 parentId / selectedChildId 连成树，
 * 展示和请求上下文都只走"当前选中"的那条链。
 * 老数据没有 rootId，首次分支时会自动补成线性链。
 */

fun Conversation.normalized(): Conversation {
    if (rootId != null || messages.isEmpty()) return this
    val msgs = messages.mapIndexed { i, m ->
        m.copy(
            parentId = messages.getOrNull(i - 1)?.id,
            selectedChildId = messages.getOrNull(i + 1)?.id
        )
    }
    return copy(rootId = msgs.first().id, messages = msgs)
}

/** 当前选中路径上的消息 */
fun Conversation.visibleMessages(): List<Msg> {
    val root = rootId ?: return messages
    val byId = messages.associateBy { it.id }
    val out = ArrayList<Msg>(messages.size)
    val seen = HashSet<String>()
    var id: String? = root
    while (id != null && seen.add(id)) {
        val m = byId[id] ?: break
        out.add(m)
        id = m.selectedChildId
    }
    return out
}

/** 同一父节点下的所有版本，按存储顺序 */
fun Conversation.versionsOf(msgId: String): List<Msg> {
    val target = messages.firstOrNull { it.id == msgId } ?: return emptyList()
    return messages.filter { it.parentId == target.parentId }
}

/** 把一串新消息挂到 parentId 下，并让 parent 选中它们 */
fun Conversation.appendBranch(parentId: String?, added: List<Msg>): Conversation {
    if (added.isEmpty()) return this
    val base = normalized()
    val first = added.first()
    val linked = added.mapIndexed { i, m ->
        m.copy(
            parentId = if (i == 0) parentId else added[i - 1].id,
            selectedChildId = added.getOrNull(i + 1)?.id
        )
    }
    val msgs = base.messages.map { m ->
        if (m.id == parentId) m.copy(selectedChildId = first.id) else m
    } + linked
    return base.copy(
        rootId = if (parentId == null) first.id else base.rootId,
        messages = msgs
    )
}

/** 切换到某个兄弟版本（只允许同一父节点之间切） */
fun Conversation.selectVersion(msgId: String): Conversation {
    val base = normalized()
    val target = base.messages.firstOrNull { it.id == msgId } ?: return this
    return if (target.parentId == null) {
        base.copy(rootId = msgId)
    } else {
        base.copy(
            messages = base.messages.map {
                if (it.id == target.parentId) it.copy(selectedChildId = msgId) else it
            }
        )
    }
}

/** 更新某个节点（流式增量写入用，不动分支关系） */
fun Conversation.patchMessage(id: String, block: (Msg) -> Msg): Conversation =
    copy(messages = messages.map { if (it.id == id) block(it) else it })
