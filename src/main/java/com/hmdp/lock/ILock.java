package com.hmdp.lock;

public interface ILock {

    boolean tryLock(long ttl);

    void unLock();

}
