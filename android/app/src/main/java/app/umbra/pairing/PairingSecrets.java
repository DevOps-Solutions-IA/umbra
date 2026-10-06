package app.umbra.pairing;

import app.umbra.core.Bytes;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

/** Standard HKDF-SHA256 / AES-256-GCM courier protection, separate from Signal sessions. */
public final class PairingSecrets {
    private static final String ALPHABET="23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    public static final int CODE_BITS=80;
    public enum Direction { INVITE, REQUEST, ACK }
    private PairingSecrets() {}
    public static char[] newCode() {
        byte[] random=Bytes.random(10);char[] code=new char[19];int bits=0,acc=0,p=0;
        try {
            for(byte b:random) { acc=(acc<<8)|(b&255);bits+=8;
                while(bits>=5) { bits-=5;if(p==4 || p==9 || p==14)code[p++]='-';code[p++]=ALPHABET.charAt((acc>>>bits)&31); }
            } return code;
        } finally { Arrays.fill(random,(byte)0); }
    }
    public static byte[] normalize(char[] code) {
        if(code==null || code.length>64)throw fail();byte[] out=new byte[16];int n=0;
        try {
            for(char c:code) { if(c==' ' || c=='-')continue;if(c>='a' && c<='z')c=(char)(c-32);
                if(ALPHABET.indexOf(c)<0 || n==16)throw fail();out[n++]=(byte)c; }
            if(n!=16)throw fail();return out;
        } catch(RuntimeException e) { Arrays.fill(out,(byte)0);throw e; }
    }
    static byte[] derive(byte[] seed,String purpose) {
        var hkdf=new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(seed,Bytes.utf8("UMBRA-PAIR-RENDEZVOUS-v1"),Bytes.utf8(purpose)));
        byte[] out=new byte[32];hkdf.generateBytes(out,0,32);return out;
    }
    public static String codeLocator(char[] code) {return codeToken(code,"UMBRA-PAIR-CODE-LOCATOR-v1");}
    public static String codeCapability(char[] code) {return codeToken(code,"UMBRA-PAIR-CODE-READ-v1");}
    private static String codeToken(char[] code,String label) {
        byte[] seed=normalize(code),key=null;try {key=derive(seed,label);return url(key);}
        finally {Arrays.fill(seed,(byte)0);if(key!=null)Arrays.fill(key,(byte)0);}
    }
    static byte[] invitationSeed(String invite) throws Exception {
        PairingService.validateInvitation(invite);
        byte[] body=Base64.getUrlDecoder().decode(invite.substring("umbra:invite:1:".length(),invite.indexOf('.')));
        try {return Base64.getUrlDecoder().decode(Bytes.text(body).split("\n",-1)[4]);}
        finally {Arrays.fill(body,(byte)0);}
    }
    public static String sealInvite(char[] code,String invite) throws Exception {
        byte[] seed=normalize(code);try {return seal(seed,Direction.INVITE,invite,invite);}finally {Arrays.fill(seed,(byte)0);}
    }
    public static String openInvite(char[] code,String blob) throws Exception {
        byte[] seed=normalize(code);try {String invite=open(seed,Direction.INVITE,null,blob);PairingService.validateInvitation(invite);return invite;}
        finally {Arrays.fill(seed,(byte)0);}
    }
    public static String sealTranscript(Direction direction,String invite,String transcript) throws Exception {
        if(direction==Direction.INVITE)throw fail();byte[] seed=invitationSeed(invite);
        try{return seal(seed,direction,invite,transcript);}finally {Arrays.fill(seed,(byte)0);}
    }
    public static String openTranscript(Direction direction,String invite,String blob) throws Exception {
        if(direction==Direction.INVITE)throw fail();byte[] seed=invitationSeed(invite);
        try{return open(seed,direction,invite,blob);}finally {Arrays.fill(seed,(byte)0);}
    }
    private static String header(Direction d,String id,String digest,long expires) {
        return "UMBRA-PAIR-BLOB-1\n"+d.name()+"\n"+id+"\n"+digest+"\n"+expires+"\n";
    }
    private static String purpose(Direction d) {return "UMBRA-PAIR-"+(d==Direction.INVITE?"CODE-INVITE":d.name())+"-v1";}
    private static String seal(byte[] seed,Direction d,String invite,String plain) throws Exception {
        if(plain==null || plain.length()>24000)throw new PairingException(PairingException.Code.PAYLOAD_TOO_LARGE);
        String aad=header(d,PairingService.invitationId(invite),Bytes.sha256(Bytes.utf8(invite)),PairingService.expires(invite));
        byte[] key=derive(seed,purpose(d)),nonce=Bytes.random(12),raw=Bytes.utf8(plain);
        try {Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));c.updateAAD(Bytes.utf8(aad));
            return Bytes.b64(Bytes.utf8(aad+url(nonce)+"\n"+url(c.doFinal(raw))+"\n"));
        } finally {Arrays.fill(key,(byte)0);Arrays.fill(raw,(byte)0);Arrays.fill(nonce,(byte)0);}
    }
    private static String open(byte[] seed,Direction d,String invite,String blob) throws Exception {
        byte[] key=null,plain=null;
        try {
            byte[] encoded=Bytes.unb64(blob,65536);String[] f=Bytes.text(encoded).split("\n",-1);
            if(f.length!=8 || !f[0].equals("UMBRA-PAIR-BLOB-1") || !f[1].equals(d.name()) || !f[7].isEmpty())throw fail();
            PairingService.token(f[2]);if(!f[3].matches("[a-f0-9]{64}") || !f[4].matches("[1-9][0-9]{0,11}"))throw fail();
            long expires=Long.parseLong(f[4]);
            if(invite!=null && (!f[2].equals(PairingService.invitationId(invite)) || !f[3].equals(Bytes.sha256(Bytes.utf8(invite))) || expires!=PairingService.expires(invite)))throw fail();
            byte[] nonce=unurl(f[5],12),cipher=unurl(f[6],24016);if(nonce.length!=12 || cipher.length<16)throw fail();
            key=derive(seed,purpose(d));Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            c.updateAAD(Bytes.utf8(header(d,f[2],f[3],expires)));plain=c.doFinal(cipher);String value=Bytes.text(plain);
            if(expires<=Bytes.now())throw new PairingException(PairingException.Code.EXPIRED);
            if(d==Direction.INVITE && (!f[2].equals(PairingService.invitationId(value)) || !f[3].equals(Bytes.sha256(Bytes.utf8(value))) || expires!=PairingService.expires(value)))throw fail();
            return value;
        } catch(PairingException e){throw e;}catch(Exception e){throw fail();}
        finally {if(key!=null)Arrays.fill(key,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    private static byte[] unurl(String value,int max) {
        if(value.length()>(max*4L+2)/3)throw fail();byte[] b=Base64.getUrlDecoder().decode(value);
        if(b.length>max || !url(b).equals(value))throw fail();return b;
    }
    private static String url(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private static PairingException fail(){return new PairingException(PairingException.Code.INVALID_FORMAT);}
}
