package com.sunmi.trans;

import android.os.Parcel;
import android.os.Parcelable;

// Parcel layout copied from the printer service; both sides must read and write
// the fields in the same order.
public class TransBean implements Parcelable {
    private byte type;
    private String text = "";
    private byte[] data;
    private int datalength;

    public TransBean(byte type, String text, byte[] data) {
        this.type = type;
        this.text = text;
        // The reader skips the byte array when its length is 0, so an empty array
        // must not be written either or every later field shifts.
        if (data != null && data.length > 0) {
            this.data = data.clone();
            this.datalength = data.length;
        }
    }

    private TransBean(Parcel in) {
        type = in.readByte();
        datalength = in.readInt();
        text = in.readString();
        if (datalength > 0) {
            data = new byte[datalength];
            in.readByteArray(data);
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeByte(type);
        out.writeInt(datalength);
        out.writeString(text);
        if (data != null) {
            out.writeByteArray(data);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<TransBean> CREATOR = new Creator<TransBean>() {
        @Override
        public TransBean createFromParcel(Parcel in) {
            return new TransBean(in);
        }

        @Override
        public TransBean[] newArray(int size) {
            return new TransBean[size];
        }
    };
}
