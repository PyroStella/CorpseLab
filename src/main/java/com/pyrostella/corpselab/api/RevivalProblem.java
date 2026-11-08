package com.pyrostella.corpselab.api;

/** 仅表示当前复活条件的问题，文本由调用方转换；无问题用 Optional.empty 表示 */
public enum RevivalProblem {
    NOT_CORPSE, SERVER_ONLY, MAIN_THREAD, REMOVED, UNSUPPORTED,
    ALREADY_REVIVING, MISSING_DATA, UUID_IN_USE, INVALID_ENTITY
}
