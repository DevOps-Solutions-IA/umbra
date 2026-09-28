package app.umbra.content;

import android.app.Service;
import android.content.Intent;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.*;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** Private one-job parser sandbox. Never receives keys, Engine, credentials or a vault handle. */
public final class RestrictedPdfService extends Service {
    static final int RENDER=1,CANCEL=2,RESULT=3;
    static final long MAX_RENDER_MILLIS=10000;
    private final Handler control=new Handler(Looper.getMainLooper());
    private final Messenger endpoint=new Messenger(new Handler(Looper.getMainLooper(),this::command));
    private boolean started;
    @Override public void onCreate() {
        super.onCreate();
        // No native PDF is opened unless the OS actually isolated this process.
        if(android.os.Process.isIsolated())control.postDelayed(this::terminate,MAX_RENDER_MILLIS);
    }
    @Override public IBinder onBind(Intent intent) {
        return android.os.Process.isIsolated()?endpoint.getBinder():null;
    }
    private boolean command(Message message) {
        if(!android.os.Process.isIsolated() || message.sendingUid!=getApplicationInfo().uid)return true;
        if(message.what==CANCEL){terminate();return true;}
        if(message.what!=RENDER || started || message.replyTo==null)return true;
        started=true;
        Bundle arguments=message.getData();
        if(!arguments.keySet().equals(Set.of("input"))){terminate();return true;}
        final ParcelFileDescriptor input;
        if(Build.VERSION.SDK_INT>=33)input=arguments.getParcelable("input",ParcelFileDescriptor.class);
        else input=legacyDescriptor(arguments);
        if(input==null){terminate();return true;}
        Messenger recipient=message.replyTo;
        new Thread(()->render(input,recipient),"umbra-isolated-pdf").start();
        return true;
    }
    @SuppressWarnings("deprecation") // Typed Parcelable overload is API 33; minSdk remains 31.
    private static ParcelFileDescriptor legacyDescriptor(Bundle arguments){return arguments.getParcelable("input");}
    private void render(ParcelFileDescriptor input,Messenger recipient) {
        byte[] packed=null;boolean success=false;
        var pages=new ArrayList<byte[]>();
        try(input;var renderer=openChecked(input)) {
            int count=renderer.getPageCount();
            if(count<1 || count>DocumentPages.MAX_PAGES)throw RestrictedPayload.invalid();
            int remaining=RestrictedPayload.MAX_BYTES-8;
            for(int index=0;index<count;index++) {
                try(var page=renderer.openPage(index)) {
                    int width=page.getWidth(),height=page.getHeight();
                    if(width<1 || height<1 || width>20000 || height>20000 ||
                            (long)Math.max(width,height)>16L*Math.min(width,height))throw RestrictedPayload.invalid();
                    float scale=Math.min(1f,(float)DocumentPages.MAX_EDGE/Math.max(width,height));
                    int w=Math.max(1,Math.round(width*scale)),h=Math.max(1,Math.round(height*scale));
                    Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                    try(var output=new LimitedOutput(remaining-4)) {
                        bitmap.eraseColor(Color.WHITE);
                        Matrix transform=new Matrix();transform.setScale(scale,scale);
                        page.render(bitmap,null,transform,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output))throw RestrictedPayload.invalid();
                        byte[] png=output.toByteArray();pages.add(png);remaining-=4+png.length;
                    } finally {bitmap.recycle();}
                }
            }
            packed=DocumentPages.pack(pages);
        } catch(Exception | OutOfMemoryError failure) {
            // No parser exception, path, document metadata or bytes enter diagnostics.
            if(packed!=null)Arrays.fill(packed,(byte)0);packed=null;
        } finally {for(byte[] page:pages)Arrays.fill(page,(byte)0);pages.clear();}
        success=packed!=null;
        try {
            Message reply=Message.obtain(null,RESULT);reply.arg1=success?1:0;reply.arg2=android.os.Process.isIsolated()?1:0;
            if(success){Bundle data=new Bundle();data.putByteArray("pages",packed);reply.setData(data);}
            recipient.send(reply);
        } catch(RemoteException unavailable) {
            // The parent cannot receive a successful result; process death still confirms closure.
        } finally {if(packed!=null)Arrays.fill(packed,(byte)0);terminate();}
    }
    private static PdfRenderer openChecked(ParcelFileDescriptor input)throws Exception {
        long size=input.getStatSize();
        if(size<1 || size>RestrictedPayload.MAX_BYTES)throw RestrictedPayload.invalid();
        return new PdfRenderer(input);
    }
    private void terminate() {
        // This endpoint can terminate only its own OS-isolated parser process.
        if(android.os.Process.isIsolated())android.os.Process.killProcess(android.os.Process.myPid());
    }
    @Override public void onDestroy(){terminate();super.onDestroy();}
    private static final class LimitedOutput extends ByteArrayOutputStream {
        private final int limit;
        LimitedOutput(int limit){if(limit<8)throw new ContentException(ContentException.Code.CAPACITY);this.limit=limit;}
        @Override public synchronized void write(byte[] bytes,int offset,int length) {
            if(length<0 || length>limit-count)throw new ContentException(ContentException.Code.CAPACITY);
            super.write(bytes,offset,length);
        }
        @Override public synchronized void write(int value) {
            if(count>=limit)throw new ContentException(ContentException.Code.CAPACITY);super.write(value);
        }
        @Override public void close(){Arrays.fill(buf,(byte)0);reset();}
    }
}
