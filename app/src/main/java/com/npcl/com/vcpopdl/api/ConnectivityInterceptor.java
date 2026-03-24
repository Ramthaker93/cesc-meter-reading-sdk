package com.npcl.com.vcpopdl.api;

import android.content.Context;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class ConnectivityInterceptor implements Interceptor {

    private Context mContext;

    public ConnectivityInterceptor(Context mContext) {
        this.mContext = mContext;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
       /* if (!Utils.isNetworkAvailable(mContext)) {
            throw new NoConnectivityException();
        }*/

        Request.Builder builder = chain.request().newBuilder();
        return chain.proceed(builder.build());
    }

    private static class NoConnectivityException extends IOException {
        @Override
        public String getMessage() {
            return "Please check network connectivity.";
        }
    }
}

