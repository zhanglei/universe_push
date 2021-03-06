package com.comsince.github.client;

import com.comsince.github.core.*;
import com.comsince.github.core.callback.CompletedCallback;
import com.comsince.github.core.callback.ConnectCallback;
import com.comsince.github.core.callback.DataCallback;
import com.comsince.github.core.future.Cancellable;
import com.comsince.github.logger.Log;
import com.comsince.github.logger.LoggerFactory;
import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;


/**
 *
 * 这里Android client，提供心跳回调机制，方便客户端自由定制
 * */
public class AndroidNIOClient implements ConnectCallback,DataCallback,CompletedCallback {
    Log log = LoggerFactory.getLogger(AndroidNIOClient.class);
    private AsyncServer asyncServer;
    private AsyncSocket asyncSocket;
    private String host;
    private int port;
    private Cancellable cancellable;

    volatile ConnectStatus connectStatus = ConnectStatus.DISCONNECT;

    Header receiveHeader = null;
    ByteBufferList receiveBuffer = new ByteBufferList();

    private PushMessageCallback pushMessageCallback;

    public void setPushMessageCallback(PushMessageCallback pushMessageCallback){
        this.pushMessageCallback = pushMessageCallback;
    }

    public AndroidNIOClient(String host, int port) {
        this.host = host;
        this.port = port;
        asyncServer = new AsyncServer(host+"-"+port);
    }

    public void connect(){
        asyncServer.post(new Runnable() {
            @Override
            public void run() {
                log.i("current connect status "+connectStatus);
                if(connectStatus == ConnectStatus.DISCONNECT){
                    connectStatus = ConnectStatus.CONNECTING;
                    cancellable = asyncServer.connectSocket(host,port,AndroidNIOClient.this);
                }
            }
        });
    }

    public void close(){
        if(cancellable != null){
            cancellable.cancel();
        }
        if(asyncSocket!= null){
            asyncSocket.close();
        }
    }

    public void sub(){
        sub("");
    }

    public void sub(String uid){
        //start register
        String tokenJson = "{\"uid\":\""+uid+"\"}";
        sendMessage(Signal.SUB, tokenJson, new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                log.e("write completed",ex);
            }
        });

    }

    /**
     * @param interval 距离下次心跳间隔，单位毫秒
     * */
    public void heart(long interval){
        log.i("Android client send heartbeat");
        String heartInterval = "{\"interval\":"+interval+"}";
        sendMessage(Signal.PING, heartInterval, new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                log.e("send heartbeat completed",ex);
            }
        });
    }

    public void sendMessage(Signal signal, String body, final CompletedCallback completedCallback){
        ByteBufferList bufferList = new ByteBufferList();
        Header header = new Header();
        header.setSignal(signal);
        header.setLength(body.getBytes().length);

        ByteBuffer allBuffer = ByteBufferList.obtain(Header.LENGTH + header.getLength());
        allBuffer.put(header.getContents());
        allBuffer.put(body.getBytes());
        allBuffer.flip();
        bufferList.add(allBuffer);

        Util.writeAll(asyncSocket, bufferList, new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                log.e("send heartbeat completed",ex);
                completedCallback.onCompleted(ex);
            }
        });
    }


    ByteBufferList notAllHeader = new ByteBufferList();

    @Override
    public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
        if(!receiveBuffer.hasRemaining() && receiveHeader == null){
            while (bb.hasRemaining()){
                int readable = bb.remaining();
                int readLength = Math.min(readable,Header.LENGTH - notAllHeader.remaining());
                bb.get(notAllHeader,readLength);
                if(notAllHeader.remaining() == Header.LENGTH){
                    byte[] header = new byte[Header.LENGTH];
                    notAllHeader.get(header);
                    if(header[0] != Header.magic){
                        log.i("this message may not header");
                        for(int i = 1; i < header.length; i++){
                            if(header[i] == Header.magic){
                                ByteBuffer rightHeader = ByteBuffer.wrap(header,i,header.length - i);
                                notAllHeader.add(rightHeader);
                                break;
                            }
                        }
                    } else {
                        receiveHeader = new Header(ByteBuffer.wrap(header));
                        break;
                    }
                }
            }
        }
        if(receiveHeader != null){
            int bodyLength = receiveHeader.getLength();
            int read = bodyLength - receiveBuffer.remaining();
            int reallyRead = read > bb.remaining() ? bb.remaining() : read;
            if(reallyRead > 0){
                bb.get(receiveBuffer,reallyRead);
            }
            if(receiveBuffer.remaining() == bodyLength){
                String message = receiveBuffer.readString(Charset.forName("UTF-8"));
                Signal receiveHeaderSignal = receiveHeader.getSignal();
                receiveHeader = null;
                String logMessage = "receive signal ["+receiveHeaderSignal+"] body-> "+message;
                log.i(logMessage);
                if(pushMessageCallback != null){
                    pushMessageCallback.receiveMessage(receiveHeaderSignal,message);
                }
            }
        }




    }

    @Override
    public void onConnectCompleted(Exception ex, AsyncSocket socket) {
        //ex为空，表示链接正常
        if(ex != null){
            if(pushMessageCallback != null){
                pushMessageCallback.receiveException(ex);
            }
            connectStatus = ConnectStatus.DISCONNECT;
            log.e("connect failed",ex);
            return;
        }


        connectStatus = ConnectStatus.CONNECTED;
        this.asyncSocket = socket;
        asyncSocket.setDataCallback(this);
        asyncSocket.setClosedCallback(this);

        if(pushMessageCallback != null){
            pushMessageCallback.onConnected();
        }
        //sub();
    }

    @Override
    public void onCompleted(Exception ex) {
        if(ex != null) {
            log.e("onCompleted ",ex);
        }
        connectStatus = ConnectStatus.DISCONNECT;

        if(pushMessageCallback != null){
            pushMessageCallback.receiveException(ex);
        }
    }


}