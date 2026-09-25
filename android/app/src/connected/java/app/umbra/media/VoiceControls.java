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
    private volatile long epoch;
    private final android.os.Handler stateHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable stateMonitor;
    public String status() { return current==null?"Sin audio":current.state().name()+" · solicitado "+(current.modulationRequested()?"MODULATED":"OFF (natural)")+" · efectivo "+current.modulationStatus(); }
    public boolean hasSession() { return current!=null; }
    public void show(Activity activity,Engine engine,String id,Executor worker,Consumer<Dialog> track,Runnable changed) {
        try { NativeDistributionPolicy.requireAuthorizedTurnDestinations(); }
        catch(SecurityException blocked) {
            Toast.makeText(activity,"Voz todavía no habilitada en esta versión. La señalización sigue disponible.",Toast.LENGTH_LONG).show();return;
        }
        if(current!=null) {
            NativeVoiceSession voice=current;
            display(activity,track,new AlertDialog.Builder(activity).setTitle("Voz: "+voice.state()+" · "+voice.modulationStatus())
                .setItems(new String[]{"Silenciar","Activar micrófono","Salida de audio","Finalizar","Solicitar video","Responder solicitud de video","Apagar video, conservar audio","Cambiar cámara","Ver video recibido","Activar/reintentar modulación local","Transmitir voz natural"},(d,which)->{
                    try {
                        if(which<2) voice.mute(which==0);
                        else if(which==9) {voice.modulation(true,false);changed.run();}
                        else if(which==10) {
                            long reviewedMode=epoch;
                            display(activity,track,new AlertDialog.Builder(activity).setTitle("Vas a transmitir tu voz natural")
                                .setMessage("Desactivar el efecto no quita el silencio ni concede permiso de micrófono.")
                                .setNegativeButton("Cancelar",null).setPositiveButton("Confirmar voz natural",(confirm,button)->{
                                    try {if(reviewedMode!=epoch)throw new SecurityException("Consentimiento caducado");voice.modulation(false,true);changed.run();}
                                    catch(Exception invalid){failure(activity);}
                                }));
                        }
                        else if(which==3) { close(); }
                        else if(which==4 || which==5) {
                            display(activity,track,new AlertDialog.Builder(activity).setTitle(which==4?"Solicitar video":"Consentimiento de video")
                                .setItems(new String[]{"Solo recibir","Enviar y recibir","Rechazar solicitud"},(choice,index)->{
                                    if(index==2) {worker.execute(()->{try {voice.rejectVideo();}catch(Exception invalid){activity.runOnUiThread(()->failure(activity));}});return;}
                                    boolean sending=index==1;
                                    if(sending && activity.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
                                        activity.requestPermissions(new String[]{Manifest.permission.CAMERA},304);return;
                                    }
                                    long clicked=SystemClock.elapsedRealtime(),reviewed=epoch;
                                    worker.execute(()->{
                                        try {
                                            if(reviewed!=epoch || SystemClock.elapsedRealtime()-clicked>30_000)throw new SecurityException("Video review expired");
                                            voice.video(sending,true,which==5,true);
                                            activity.runOnUiThread(()->{if(reviewed==epoch)changed.run();});
                                        } catch(Exception invalid){activity.runOnUiThread(()->failure(activity));}
                                    });
                                }));
                        } else if(which==6) {
                            voice.requestVideoStop();
                        } else if(which==7) {
                            worker.execute(()->{try {voice.switchCamera();}catch(Exception invalid){activity.runOnUiThread(()->failure(activity));}});
                        } else if(which==8) VideoSurface.show(activity,voice,track);
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
        CheckBox modulated=new CheckBox(activity);modulated.setText("Modulación local (desactivada: voz natural)");
        modulated.setSaveEnabled(false);fields.addView(modulated);
        TextView notice=new TextView(activity);notice.setText("El efecto cambia el timbre; no garantiza que no puedan reconocerte. Ante un fallo, el audio se silencia.");fields.addView(notice);
        EditText url=field(activity,fields,"TURN autorizado localmente (turn: o turns:)",false);
        EditText user=field(activity,fields,"Usuario temporal",false);
        EditText password=field(activity,fields,"Credencial temporal",true);
        display(activity,track,new AlertDialog.Builder(activity).setTitle("Consentimiento de voz — solo TURN")
            .setMessage("Sesión "+id+". Confirma el dispositivo seleccionado. Usa únicamente tu retransmisor autorizado y credenciales temporales. Máximo 180 s; bloquear o salir detiene el audio. El operador TURN observa metadatos.")
            .setView(fields).setNegativeButton("Cancelar",null).setPositiveButton("Autorizar micrófono",(dialog,which)->{
                String endpoint=url.getText().toString().trim(),username=user.getText().toString(),secret=password.getText().toString();
                url.setText(""); user.setText(""); password.setText("");
                boolean requestedModulation=modulated.isChecked();
                long clicked=SystemClock.elapsedRealtime();
                worker.execute(()->{
                    TurnConfiguration turn=null; app.umbra.calls.CallService.MediaLease lease=null;
                    try {
                        if(SystemClock.elapsedRealtime()-clicked>30_000) throw new SecurityException("Media consent expired in queue");
                        String revision=Bytes.sha256(Bytes.utf8(endpoint+"\n"+username));
                        turn=new TurnConfiguration(List.of(endpoint),revision,username,secret,180000,SystemClock::elapsedRealtime);
                        lease=engine.calls().prepareMedia(engine.calls().reviewMedia(id,revision),true);
                        if(reviewed!=epoch) throw new SecurityException("Media consent cancelled while queued");
                        final NativeVoiceSession opened=NativeVoiceSession.open(activity,lease,turn,requestedModulation);
                        activity.runOnUiThread(()->{
                            if(reviewed!=epoch || activity.isFinishing() || activity.isDestroyed()) { opened.close();return; }
                            current=opened;monitor(activity,opened,changed);changed.run();
                        });
                    } catch(Exception invalid) {
                        if(lease!=null) lease.close(); if(turn!=null) turn.close();
                        activity.runOnUiThread(()->{if(reviewed==epoch) failure(activity);});
                    }
                });
            }));
    }
    private void monitor(Activity activity,NativeVoiceSession voice,Runnable changed) {
        if(stateMonitor!=null)stateHandler.removeCallbacks(stateMonitor);
        stateMonitor=new Runnable() {
            private String last="";
            @Override public void run() {
                if(current!=voice || activity.isFinishing() || activity.isDestroyed())return;
                String now=status();
                if(!now.equals(last)) {
                    last=now;changed.run();
                    if(voice.modulationStatus().equals("ERROR_MUTED") && voice.state()==NativeVoiceSession.State.ACTIVE)
                        Toast.makeText(activity,"Error de modulación: audio silenciado. Reintenta o confirma voz natural.",Toast.LENGTH_LONG).show();
                }
                stateHandler.postDelayed(this,250);
            }
        };
        stateHandler.post(stateMonitor);
    }
    private static EditText field(Activity activity,LinearLayout parent,String hint,boolean secret) {
        EditText field=new EditText(activity);field.setHint(hint);field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        field.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setSaveEnabled(false);field.setSaveFromParentEnabled(false);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        parent.addView(field);return field;
    }
    private static void display(Activity activity,Consumer<Dialog> track,AlertDialog.Builder builder) {
        AlertDialog dialog=builder.create(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);track.accept(dialog);dialog.show();
    }
    private static void failure(Activity activity) { Toast.makeText(activity,"No se pudo establecer la conexión privada mediante el retransmisor.",Toast.LENGTH_LONG).show(); }
    @Override public void close() { if(stateMonitor!=null)stateHandler.removeCallbacks(stateMonitor);stateMonitor=null;epoch++;NativeVoiceSession voice=current;current=null;if(voice!=null) voice.close(); }
}
