package com.npcl.com.vcpopdl.api;

import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface APIInterface {
    @POST("api/Account/GetToken")
    Call<JsonObject> getheaderToken(@Body JsonObject body);

    @GET("api/FetchOutstanding/FindOutStandingDues")
    Call<JsonObject> refreshPayment(@Query("PartnerID") String PartnerID, @Query("ConsumerNo") String ConsumerNo);
}
