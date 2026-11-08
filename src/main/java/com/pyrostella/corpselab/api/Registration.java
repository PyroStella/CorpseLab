package com.pyrostella.corpselab.api;

/** 可撤销的扩展注册；重复关闭无副作用，不会删除后来注册的同名扩展 */
@FunctionalInterface
public interface Registration extends AutoCloseable {
    @Override
    void close();
}
