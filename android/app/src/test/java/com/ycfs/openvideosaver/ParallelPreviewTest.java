package com.ycfs.openvideosaver;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ParallelPreviewTest {
    @Test public void transfersOverlapWithinLimitAndKeepOriginalIndices() throws Exception {
        ParallelPreview<String> batch=new ParallelPreview<>();CountDownLatch started=new CountDownLatch(3),release=new CountDownLatch(1),second=new CountDownLatch(1),all=new CountDownLatch(7);
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();AtomicReference<Exception> failure=new AtomicReference<>();
        String[] result=new String[7];List<Integer> order=Collections.synchronizedList(new ArrayList<>());
        try {
            batch.start(7,index->{
                int current=active.incrementAndGet();peak.accumulateAndGet(current,Math::max);
                try{if(index<3){started.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new TimeoutException();}
                    if(index==0&&!second.await(5,TimeUnit.SECONDS))throw new TimeoutException();return "original-"+index;
                }finally{active.decrementAndGet();}
            },new ParallelPreview.Listener<String>() {
                public void loaded(int index,String value){result[index]=value;order.add(index);if(index==2)second.countDown();all.countDown();}
                public void failed(Exception error){failure.set(error);while(all.getCount()>0)all.countDown();}
            });
            assertTrue("three transfers must start together",started.await(5,TimeUnit.SECONDS));assertEquals(3,peak.get());release.countDown();
            assertTrue(all.await(5,TimeUnit.SECONDS));assertNull(failure.get());assertTrue(peak.get()<=3);
            assertTrue("completion order can differ from source order",order.indexOf(2)<order.indexOf(0));
            for(int i=0;i<7;i++)assertEquals("original-"+i,result[i]);
        }finally{release.countDown();second.countDown();batch.cancel();}
    }
    @Test public void simultaneousErrorsReportOnce() throws Exception {
        ParallelPreview<String> batch=new ParallelPreview<>();CountDownLatch started=new CountDownLatch(3),release=new CountDownLatch(1),exited=new CountDownLatch(3),failed=new CountDownLatch(1);
        AtomicInteger failures=new AtomicInteger(),loaded=new AtomicInteger();
        try {
            batch.start(3,i->{try{started.countDown();release.await();throw new java.io.IOException("fixture failed");}finally{exited.countDown();}},new ParallelPreview.Listener<String>(){
                public void loaded(int index,String value){loaded.incrementAndGet();}
                public void failed(Exception error){failures.incrementAndGet();failed.countDown();}
            });
            assertTrue(started.await(5,TimeUnit.SECONDS));release.countDown();assertTrue(failed.await(5,TimeUnit.SECONDS));assertTrue(exited.await(5,TimeUnit.SECONDS));
            assertEquals(1,failures.get());assertEquals(0,loaded.get());
        }finally{release.countDown();batch.cancel();}
    }
    @Test public void cancellationStopsQueuedPreviewsWithoutSuccessOrErrorCallbacks() throws Exception {
        ParallelPreview<String> batch=new ParallelPreview<>();CountDownLatch started=new CountDownLatch(3),blocked=new CountDownLatch(1),exited=new CountDownLatch(3);
        AtomicInteger loads=new AtomicInteger(),callbacks=new AtomicInteger();
        try {
            batch.start(6,i->{loads.incrementAndGet();try{started.countDown();blocked.await();return "unused";}finally{exited.countDown();}},new ParallelPreview.Listener<String>(){
                public void loaded(int index,String value){callbacks.incrementAndGet();}
                public void failed(Exception error){callbacks.incrementAndGet();}
            });
            assertTrue(started.await(5,TimeUnit.SECONDS));batch.cancel();assertTrue(exited.await(5,TimeUnit.SECONDS));assertEquals(3,loads.get());assertEquals(0,callbacks.get());
        }finally{blocked.countDown();batch.cancel();}
    }
}
