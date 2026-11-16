package com.mycompany.bai4.net;

import java.nio.ByteBuffer;

/**
 * Cấu trúc gói tin UDP chuẩn cho truyền tải Audio, Video và Control.
 * Header cố định: 33 bytes. Payload tối đa: 1200 bytes.
 */
public class UdpPacket {

    public static final int HEADER_SIZE = 33;
    public static final int MAX_PAYLOAD_SIZE = 1200;
    public static final int MAX_PACKET_SIZE = HEADER_SIZE + MAX_PAYLOAD_SIZE;

    public static final byte TYPE_AUDIO             = 1;
    public static final byte TYPE_VIDEO             = 2;
    public static final byte TYPE_CONTROL_PROBE     = 3;
    public static final byte TYPE_CONTROL_ACK       = 4;
    public static final byte TYPE_CONTROL_HEARTBEAT = 5;
    public static final byte TYPE_CONTROL_CAM_OFF   = 6;
    public static final byte TYPE_CONTROL_BYE       = 7;

    private final long callId;
    private final byte packetType;
    private final int sequenceNumber;
    private final long timestamp;
    private final int frameId;
    private final short fragmentIndex;
    private final short fragmentCount;
    private final int payloadLength;
    private final byte[] payload;

    public UdpPacket(long callId, byte packetType, int sequenceNumber, long timestamp,
                     int frameId, int fragmentIndex, int fragmentCount, byte[] payload) {
        this.callId = callId;
        this.packetType = packetType;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
        this.frameId = frameId;
        this.fragmentIndex = (short) fragmentIndex;
        this.fragmentCount = (short) fragmentCount;
        this.payload = (payload != null) ? payload : new byte[0];
        this.payloadLength = this.payload.length;
    }

    public long getCallId()          { return callId; }
    public byte getPacketType()      { return packetType; }
    public int getSequenceNumber()   { return sequenceNumber; }
    public long getTimestamp()       { return timestamp; }
    public int getFrameId()          { return frameId; }
    public short getFragmentIndex()  { return fragmentIndex; }
    public short getFragmentCount()  { return fragmentCount; }
    public int getPayloadLength()    { return payloadLength; }
    public byte[] getPayload()       { return payload; }

    public byte[] toBytes() {
        int len = Math.min(payloadLength, MAX_PAYLOAD_SIZE);
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + len);
        buf.putLong(callId);
        buf.put(packetType);
        buf.putInt(sequenceNumber);
        buf.putLong(timestamp);
        buf.putInt(frameId);
        buf.putShort(fragmentIndex);
        buf.putShort(fragmentCount);
        buf.putInt(len);
        if (len > 0) {
            buf.put(payload, 0, len);
        }
        return buf.array();
    }

    public static UdpPacket fromBytes(byte[] data, int offset, int length) {
        if (data == null || length < HEADER_SIZE) return null;
        ByteBuffer buf = ByteBuffer.wrap(data, offset, length);
        long callId = buf.getLong();
        byte type = buf.get();
        int seq = buf.getInt();
        long ts = buf.getLong();
        int fId = buf.getInt();
        short fragIdx = buf.getShort();
        short fragCnt = buf.getShort();
        int payLen = buf.getInt();

        if (payLen < 0 || payLen > MAX_PAYLOAD_SIZE || (HEADER_SIZE + payLen) > length) {
            return null;
        }
        if (fragCnt <= 0 || fragIdx < 0 || fragIdx >= fragCnt) {
            return null;
        }

        byte[] payload = new byte[payLen];
        if (payLen > 0) {
            buf.get(payload, 0, payLen);
        }
        return new UdpPacket(callId, type, seq, ts, fId, fragIdx, fragCnt, payload);
    }
}