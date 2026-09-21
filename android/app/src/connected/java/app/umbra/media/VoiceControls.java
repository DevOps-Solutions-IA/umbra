package app.umbra.media;

import android.Manifest;
import android.app.*;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Minimal foreground consent controls. No remote configuration, credential persistence or auto-resume. */
public final class VoiceControls implements AutoCloseable {
    private NativeVoiceSession current;
    private long epoch;
    public String status() { return current==null?"Sin audio":current.state().name(); }
    public boolean hasSession() { return current!=null; }
    public void show(Activity activity,Engine engine,String id,Executor worker,Consumer<Dialog> track,Runnable changed) {
        if(current!=null) {
            NativeVoiceSession voice=current;
            display(activity,track,new AlertDialog.Builder(activity).setTitle("Voz: "+voice.state())
                .setItems(new String[]{"Silenciar","Activar micrófono","Salida de audio","Finalizar"},(d,which)->{
                    try {
                        if(which<2) voice.mute(which==0);
                        else if(which==3) { close(); }
                        else {
                            var devices=voice.communicationDevices();
                            String[] labels=devices.stream().map(device->device.getProductName().toString()).toArray(String[]::new);
                            display(activity,track,new AlertDialog.Builder(activity).setTitle("Salida de comunicación")
                                .setItems(labels,(dialog,index)->{ try { voice.selectCommunicationDevice(devices.get(index).getId()); } catch(Exception invalid) { close(); failure(activity); } }));
                        }
                    } catch(Exception invalid) { close(); failure(activity); }
                }));
            return;
        }
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},303);
            return; // Never start from the permission callback. Unlock/review again if the dialog paused the app.
        }
        long reviewed=epoch;
        LinearLayout fields=new LinearLayout(activity); fields.setOrientation(LinearLayout.VERTICAL);
        EditText url=field(activity,fields,"TURN autorizado localmente (turn: o turns:)",false);
        EditText user=field(activity,fields,"Usuario temporal",false);
        EditText password=field(activity,fields,"Credencial temporal",true);
        display(activity,track,new AlertDialog.Builder(activity).setTitle("Consentimiento de voz — solo TURN")
            .setMessage("Sesión "+id+". Confirma el dispositivo seleccionado. Usa únicamente tu retransmisor autorizado y credenciales temporales. Máximo 180 s; bloquear o salir detiene el audio. El operador TURN observa metadatos.")
            .setView(fields).setNegativeButton("Cancelar",null).setPositiveButton("Autorizar micrófono",(dialog,which)->{
                String endpoint=url.getText().toString().trim(),username=user.getText().toString(),secret=password.getText().toString();
                url.setText(""); user.setText(""); password.setText("");
                long clicked=SystemClock.elapsedRealtime();
                worker.execute(()->{
                    TurnConfiguration turn=null; app.umbra.calls.CallService.MediaLease lease=null;
                    try {
                        if(SystemClock.elapsedRealtime()-clicked>30_000) throw new SecurityException("Media consent expired in queue");
                        String revision=Bytes.sha256(Bytes.utf8(endpoint+"\n"+username));
                        turn=new TurnConfiguration(List.of(endpoint),revision,username,secret,180000,SystemClock::elapsedRealtime);
                        lease=engine.calls().prepareMedia(engine.calls().reviewMedia(id,revision),true);
                        final var approved=lease; final var configuration=turn;
                        activity.runOnUiThread(()->{
                            if(reviewed!=epoch || activity.isFinishing() || activity.isDestroyed()) { approved.close();configuration.close();return; }
                            try { current=NativeVoiceSession.open(activity,approved,configuration);changed.run(); }
                            catch(Exception invalid) { approved.close(); configuration.close(); failure(activity); }
                        });
                    } catch(Exception invalid) {
                        if(lease!=null) lease.close(); if(turn!=null) turn.close();
                        activity.runOnUiThread(()->{if(reviewed==epoch) failure(activity);});
                    }
                });
            }));
    }
    private static EditText field(Activity activity,LinearLayout parent,String hint,boolean secret) {
        EditText field=new EditText(activity);field.setHint(hint);field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        field.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        parent.addView(field);return field;
    }
    private static void display(Activity activity,Consumer<Dialog> track,AlertDialog.Builder builder) {
        AlertDialog dialog=builder.create(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);track.accept(dialog);dialog.show();
    }
    private static void failure(Activity activity) { Toast.makeText(activity,"No se pudo establecer la conexión privada mediante el retransmisor.",Toast.LENGTH_LONG).show(); }
    @Override public void close() { epoch++;NativeVoiceSession voice=current;current=null;if(voice!=null) voice.close(); }
}
