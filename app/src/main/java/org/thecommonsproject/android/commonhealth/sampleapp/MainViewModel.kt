package org.thecommonsproject.android.commonhealth.sampleapp

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.flow.*
import org.thecommonsproject.android.common.interapp.CommonHealthAuthorizationStatus
import org.thecommonsproject.android.common.interapp.dataquery.response.ClinicalDataQueryResult
import org.thecommonsproject.android.common.interapp.dataquery.response.DataQueryResult
import org.thecommonsproject.android.common.interapp.scope.DataType
import org.thecommonsproject.android.common.interapp.scope.Scope
import org.thecommonsproject.android.common.interapp.scope.ScopeRequest
import org.thecommonsproject.android.commonhealthclient.AuthorizationManagementActivity
import org.thecommonsproject.android.commonhealthclient.AuthorizationRequest
import org.thecommonsproject.android.commonhealthclient.CommonHealthStore
import timber.log.Timber
import java.util.*

class MainViewModel(
    private val commonHealthStore: CommonHealthStore
) : ViewModel() {

    private val connectionAlias = "connection_alias"
    private val TAG by lazy { MainViewModel::class.java.simpleName }

    val allDataTypes: List<DataType.ClinicalResource> = listOf(
        DataType.ClinicalResource.AllergyIntoleranceResource,
        DataType.ClinicalResource.ClinicalVitalsResource,
        DataType.ClinicalResource.ConditionsResource,
        DataType.ClinicalResource.ImmunizationsResource,
        DataType.ClinicalResource.LaboratoryResultsResource,
        DataType.ClinicalResource.MedicationResource,
        DataType.ClinicalResource.ProceduresResource
    )


    val scopeRequest: ScopeRequest by lazy {
        val builder = ScopeRequest.Builder()
        allDataTypes.forEach {
            builder.add(it, Scope.Access.READ)
        }
        builder.build()
    }

    sealed class ResultHolderMessage {
        class SetResults(val resourceType: DataType.ClinicalResource, val results: List<ClinicalDataQueryResult>) : ResultHolderMessage()
    }


//    private var resultsMap: Map<DataType.ClinicalResource, List<ClinicalDataQueryResult>> = emptyMap()
//    var resultsLiveData: MutableLiveData<Map<DataType.ClinicalResource, List<ClinicalDataQueryResult>>> = MutableLiveData(emptyMap())
    val resultsFlow: Flow<Map<DataType.ClinicalResource, List<ClinicalDataQueryResult>>>
//    val resultsLiveData: LiveData<Map<DataType.ClinicalResource, List<ClinicalDataQueryResult>>>
    // This function launches a new counter actor
    fun CoroutineScope.resultsHolderActor(sendChannel: SendChannel<Map<DataType.ClinicalResource, List<ClinicalDataQueryResult>>>) = actor<ResultHolderMessage> {
        var resultsMap: Map<DataType.ClinicalResource, List<ClinicalDataQueryResult>> = emptyMap()
        for (msg in channel) { // iterate over incoming messages
            when (msg) {
                is ResultHolderMessage.SetResults -> {

                    when (val existingResults = resultsMap[msg.resourceType]) {
                        null -> {}
                        else -> { assert(existingResults.count() == msg.results.count()) }
                    }

                    resultsMap = resultsMap.plus(Pair(msg.resourceType, msg.results))
//                    resultsLiveData.postValue(resultsMap)
                    sendChannel.send(resultsMap)
                }
            }
        }
    }

    var resultsHolderActor: SendChannel<ResultHolderMessage>? = null
    init {
        resultsFlow = callbackFlow {
            this@MainViewModel.resultsHolderActor = resultsHolderActor(channel)
        }
//        resultsLiveData = resultsFlow.asLiveData()
//        viewModelScope.launch {
//            this@MainViewModel.resultsHolderActor = resultsHolderActor()
//        }
    }

    fun getResultsLiveData(context: Context) : LiveData<Map<DataType.ClinicalResource, List<ClinicalDataQueryResult>>> {


        return callbackFlow<Map<DataType.ClinicalResource, List<ClinicalDataQueryResult>>> {

            val a = resultsHolderActor(this.channel)

            //fetch all data
            val fetchFlow = fetchAllDataFlow(context)

            fetchFlow.collect {
                val message = ResultHolderMessage.SetResults(it.first, it.second)
                a.send(message)
            }

        }.asLiveData()

//
//
//
//        return resultsFlow.asLiveData()
    }

    suspend fun checkAuthorizationStatus(
        context: Context
    ) : CommonHealthAuthorizationStatus {
        return try {
            commonHealthStore.checkAuthorizationStatus(
                context,
                connectionAlias,
                scopeRequest
            )
        } catch (e: Exception) {
            CommonHealthAuthorizationStatus.cannotAuthorize
        }
    }

    fun generateAuthIntent(
        context: Context
    ) : Intent {
        val authorizationRequest = AuthorizationRequest(
            connectionAlias,
            scopeRequest,
            false,
            "Sample app would like to read your labs, vitals, and conditions."
        )

        return AuthorizationManagementActivity.createStartForResultIntent(
            context,
            authorizationRequest
        )
    }

    private suspend fun fetchData(context: Context, clinicalResource: DataType.ClinicalResource) : List<DataQueryResult>{
        return try {
            commonHealthStore.readSampleQuery(
                context,
                connectionAlias,
                setOf(clinicalResource)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Exception fetching data: ", e)
            emptyList()
        }
    }

//    suspend fun fetchAllData(context: Context) {
//
//
//        val typesToFetch = allDataTypes
//        val jobs = typesToFetch.map { clinicalResource ->
//
//            CoroutineScope(Dispatchers.IO).launch {
//                val results = fetchData(context, clinicalResource).mapNotNull { it as? ClinicalDataQueryResult }
//                resultsHolderActor!!.send(
//                    ResultHolderMessage.SetResults(
//                        clinicalResource,
//                        results
//                    )
//                )
//            }
//
//        }
//
//        jobs.joinAll()
//    }

    private suspend fun fetchAllDataFlow(context: Context) : Flow<Pair<DataType.ClinicalResource, List<ClinicalDataQueryResult>>> {
        return allDataTypes.asFlow().map { clinicalResource ->
            val resources = fetchData(context, clinicalResource).mapNotNull { it as? ClinicalDataQueryResult }
            Pair(clinicalResource, resources)
        }
    }

    suspend fun isCommonHealthAvailable(context: Context): Boolean {
        return try {
            commonHealthStore.isCommonHealthAvailable(context)
        }
        catch (e: Exception) {
            false
        }
    }

    fun prettyPrintJSON(jsonString: String): String {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val element = JsonParser.parseString(jsonString)
        return gson.toJson(element)
    }

}