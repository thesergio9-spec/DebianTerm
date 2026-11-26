/*
 * Derived from Termux:X11 (https://github.com/termux/termux-x11)
 * Licensed under GNU General Public License v3.0 (GPL-3.0).
 */
package com.termux.x11;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.view.Surface;

/**
 * Pure-Java Binder contract matching Termux:X11's {@code ICmdEntryInterface.aidl}.
 */
public interface ICmdEntryInterface extends IInterface {

    String DESCRIPTOR = "com.termux.x11.ICmdEntryInterface";

    ParcelFileDescriptor getXConnection() throws RemoteException;

    ParcelFileDescriptor getLogcatOutput() throws RemoteException;

    void reportFatalError(String message) throws RemoteException;

    void windowChanged(Surface surface) throws RemoteException;

    void cursorWindowChanged(Surface surface) throws RemoteException;

    /** Server-side Binder Stub implemented by {@link CmdEntryPoint}. */
    abstract class Stub extends Binder implements ICmdEntryInterface {

        static final int TRANSACTION_getXConnection = FIRST_CALL_TRANSACTION;
        static final int TRANSACTION_getLogcatOutput = FIRST_CALL_TRANSACTION + 1;
        static final int TRANSACTION_reportFatalError = FIRST_CALL_TRANSACTION + 2;
        static final int TRANSACTION_windowChanged = FIRST_CALL_TRANSACTION + 3;
        static final int TRANSACTION_cursorWindowChanged = FIRST_CALL_TRANSACTION + 4;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ICmdEntryInterface asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof ICmdEntryInterface) {
                return (ICmdEntryInterface) iin;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
                data.enforceInterface(DESCRIPTOR);
            }
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            switch (code) {
                case TRANSACTION_getXConnection: {
                    ParcelFileDescriptor result = getXConnection();
                    reply.writeNoException();
                    writeTypedObject(reply, result, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    return true;
                }
                case TRANSACTION_getLogcatOutput: {
                    ParcelFileDescriptor result = getLogcatOutput();
                    reply.writeNoException();
                    writeTypedObject(reply, result, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    return true;
                }
                case TRANSACTION_reportFatalError: {
                    String message = data.readString();
                    reportFatalError(message);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_windowChanged: {
                    Surface surface = readSurface(data);
                    windowChanged(surface);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_cursorWindowChanged: {
                    Surface surface = readSurface(data);
                    cursorWindowChanged(surface);
                    reply.writeNoException();
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static Surface readSurface(Parcel data) {
            if (data.readInt() != 0) {
                return Surface.CREATOR.createFromParcel(data);
            }
            return null;
        }

        private static void writeTypedObject(Parcel reply, Parcelable value, int parcelableFlags) {
            if (value != null) {
                reply.writeInt(1);
                value.writeToParcel(reply, parcelableFlags);
            } else {
                reply.writeInt(0);
            }
        }

        private static final class Proxy implements ICmdEntryInterface {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public ParcelFileDescriptor getXConnection() throws RemoteException {
                return transactFd(TRANSACTION_getXConnection);
            }

            @Override
            public ParcelFileDescriptor getLogcatOutput() throws RemoteException {
                return transactFd(TRANSACTION_getLogcatOutput);
            }

            private ParcelFileDescriptor transactFd(int code) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(code, data, reply, 0);
                    reply.readException();
                    if (reply.readInt() != 0) {
                        return ParcelFileDescriptor.CREATOR.createFromParcel(reply);
                    }
                    return null;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void reportFatalError(String message) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(message);
                    mRemote.transact(TRANSACTION_reportFatalError, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void windowChanged(Surface surface) throws RemoteException {
                transactSurface(TRANSACTION_windowChanged, surface);
            }

            @Override
            public void cursorWindowChanged(Surface surface) throws RemoteException {
                transactSurface(TRANSACTION_cursorWindowChanged, surface);
            }

            private void transactSurface(int code, Surface surface) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    if (surface != null) {
                        data.writeInt(1);
                        surface.writeToParcel(data, 0);
                    } else {
                        data.writeInt(0);
                    }
                    mRemote.transact(code, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
