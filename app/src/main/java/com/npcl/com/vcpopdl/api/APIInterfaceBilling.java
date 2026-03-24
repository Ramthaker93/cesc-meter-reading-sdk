package com.npcl.com.vcpopdl.api;

import com.google.gson.JsonObject;
import com.npcl.com.vcpopdl.model.InvoicesResponse;
import com.npcl.com.vcpopdl.model.QuickBillResponse;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface APIInterfaceBilling {
    @GET("sap/opu/odata/sap/ZMCF002_SRV/QuickBillSet('{consumerNo}')?$format=json")
    Call<QuickBillResponse> getQuickBillData(
            @Path("consumerNo") String consumerNo
    );

    @GET("sap/opu/odata/sap/erp_isu_umc/Accounts('{consumerNo}')/Invoices?$format=json")
    Call<InvoicesResponse> getInvoices(
            @Path("consumerNo") String consumerNo
    );

}
