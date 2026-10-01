package com.ycfs.openvideosaver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** A single preview batch, bounded to three transfers, retaining original picture indices. */
final class ParallelPreview<T> {
    interface Source<T> { T load(int index) throws Exception; }
    interface Listener<T> { void loaded(int index,T value); void failed(Exception error); }
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final AtomicBoolean stopped=new AtomicBoolean();
    void start(int size,Source<T> source,Listener<T> listener) {
        if(size==0){workers.shutdown();return;}
        AtomicInteger remaining=new AtomicInteger(size);
        for(int i=0;i<size&&!stopped.get();i++) {
            final int index=i;
            try{workers.submit(()->{
                try{if(stopped.get())return;T value=source.load(index);if(!stopped.get())listener.loaded(index,value);}
                catch(Exception error){if(stopped.compareAndSet(false,true)){workers.shutdownNow();listener.failed(error);}}
                finally{if(remaining.decrementAndGet()==0)workers.shutdown();}
            });}catch(RejectedExecutionException error){if(stopped.compareAndSet(false,true)){workers.shutdownNow();listener.failed(error);}}
        }
    }
    void cancel(){stopped.set(true);workers.shutdownNow();}
}
