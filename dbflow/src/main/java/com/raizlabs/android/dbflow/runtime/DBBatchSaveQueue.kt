package com.raizlabs.android.dbflow.runtime

import android.os.Looper
import com.raizlabs.android.dbflow.config.DatabaseDefinition
import com.raizlabs.android.dbflow.config.FlowLog
import com.raizlabs.android.dbflow.config.FlowManager
import com.raizlabs.android.dbflow.structure.Model
import com.raizlabs.android.dbflow.structure.database.transaction.ProcessModelTransaction
import com.raizlabs.android.dbflow.structure.database.transaction.Transaction
import java.util.*

/**
 * Description: This queue will bulk save items added to it when it gets access to the DB. It should only exist as one entity.
 * It will save the [.MODEL_SAVE_SIZE] at a time or more only when the limit is reached. It will not
 */
class DBBatchSaveQueue
/**
 * Creates a new instance of this class to batch save DB object classes.
 */
internal constructor(private val databaseDefinition: DatabaseDefinition) : Thread("DBBatchSaveQueue") {

    /**
     * Tells how many items to save at a time. This can be set using [.setModelSaveSize]
     */
    private var modelSaveSize = MODEL_SAVE_SIZE

    /**
     * Sets the time we check periodically for leftover DB objects in our queue to save.
     */
    private var modelSaveCheckTime = sMODEL_SAVE_CHECK_TIME.toLong()

    /**
     * The list of DB objects that we will save here
     */
    private val models: ArrayList<Any> = arrayListOf()

    /**
     * If true, this queue will quit.
     */
    private var isQuitting = false

    private var errorListener: Transaction.Error? = null
    private var successListener: Transaction.Success? = null
    private var emptyTransactionListener: Runnable? = null

    private val modelSaver = ProcessModelTransaction.ProcessModel<Any?> { model, _ ->
        (model as? Model)?.save() ?: if (model != null) {
            val modelClass = model.javaClass
            FlowManager.getModelAdapter(modelClass).save(model)
        }
    }

    private val successCallback = Transaction.Success { transaction ->
        successListener?.onSuccess(transaction)
    }

    private val errorCallback = Transaction.Error { transaction, error ->
        errorListener?.onError(transaction, error)
    }

    /**
     * Sets how many models to save at a time in this queue.
     * Increase it for larger batches, but slower recovery time.
     * Smaller the batch, the more time it takes to save overall.
     */
    fun setModelSaveSize(mModelSaveSize: Int) {
        this.modelSaveSize = mModelSaveSize
    }

    /**
     * Sets how long, in millis that this queue will check for leftover DB objects that have not been saved yet.
     * The default is [.sMODEL_SAVE_CHECK_TIME]
     *
     * @param time The time, in millis that queue automatically checks for leftover DB objects in this queue.
     */
    fun setModelSaveCheckTime(time: Long) {
        this.modelSaveCheckTime = time
    }


    /**
     * Listener for errors in each batch [Transaction]. Called from the DBBatchSaveQueue thread.
     *
     * @param errorListener The listener to use.
     */
    fun setErrorListener(errorListener: Transaction.Error?) {
        this.errorListener = errorListener
    }

    /**
     * Listener for batch updates. Called from the DBBatchSaveQueue thread.
     *
     * @param successListener The listener to get notified when changes are successful.
     */
    fun setSuccessListener(successListener: Transaction.Success?) {
        this.successListener = successListener
    }

    /**
     * Listener for when there is no work done. Called from the DBBatchSaveQueue thread.
     *
     * @param emptyTransactionListener The listener to get notified when the save queue thread ran but was empty.
     */
    fun setEmptyTransactionListener(emptyTransactionListener: Runnable?) {
        this.emptyTransactionListener = emptyTransactionListener
    }

    override fun run() {
        super.run()
        Looper.prepare()
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        while (true) {
            var tmpModels = listOf<Any>()
            synchronized(models) {
                tmpModels = arrayListOf(models)
                models.clear()
            }
            if (tmpModels.isNotEmpty()) {
                databaseDefinition.beginTransactionAsync(
                        ProcessModelTransaction.Builder(modelSaver)
                                .addAll(tmpModels)
                                .build())
                        .success(successCallback)
                        .error(errorCallback)
                        .build()
                        .execute()
            } else {
                emptyTransactionListener?.run()
            }

            try {
                //sleep, and then check for leftovers
                Thread.sleep(modelSaveCheckTime)
            } catch (e: InterruptedException) {
                FlowLog.log(FlowLog.Level.I, "DBRequestQueue Batch interrupted to start saving")
            }

            if (isQuitting) {
                return
            }
        }
    }

    /**
     * Will cause the queue to wake from sleep and handle it's current list of items.
     */
    fun purgeQueue() {
        interrupt()
    }

    /**
     * Adds an object to this queue.
     */
    fun add(inModel: Any) {
        synchronized(models) {
            models.add(inModel)

            if (models.size > modelSaveSize) {
                interrupt()
            }
        }
    }

    /**
     * Adds a [java.util.Collection] of DB objects to this queue
     */
    fun addAll(list: Collection<Any>) {
        synchronized(models) {
            models.addAll(list)

            if (models.size > modelSaveSize) {
                interrupt()
            }
        }
    }

    /**
     * Adds a [java.util.Collection] of class that extend Object to this queue
     */
    fun addAll2(list: Collection<*>) {
        synchronized(models) {
            models.addAll(list)

            if (models.size > modelSaveSize) {
                interrupt()
            }
        }
    }

    /**
     * Removes a DB object from this queue before it is processed.
     */
    fun remove(outModel: Any) {
        synchronized(models) {
            models.remove(outModel)
        }
    }

    /**
     * Removes a [java.util.Collection] of DB object from this queue
     * before it is processed.
     */
    fun removeAll(outCollection: Collection<Any>) {
        synchronized(models) {
            models.removeAll(outCollection)
        }
    }

    /**
     * Removes a [java.util.Collection] of DB objects from this queue
     * before it is processed.
     */
    fun removeAll2(outCollection: Collection<*>) {
        synchronized(models) {
            models.removeAll(outCollection)
        }
    }

    /**
     * Quits this queue after it sleeps for the [.modelSaveCheckTime]
     */
    fun quit() {
        isQuitting = true
    }

    companion object {

        /**
         * Once the queue size reaches 50 or larger, the thread will be interrupted and we will batch save the models.
         */
        private val MODEL_SAVE_SIZE = 50

        /**
         * The default time that it will awake the save queue thread to check if any models are still waiting to be saved
         */
        private val sMODEL_SAVE_CHECK_TIME = 30000
    }

}
