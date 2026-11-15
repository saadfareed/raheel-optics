package pk.raheeloptics.orders

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import pk.raheeloptics.orders.core.*
import pk.raheeloptics.orders.identity.Session
import pk.raheeloptics.orders.identity.SessionStore
import pk.raheeloptics.orders.ui.OrdersViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** "Load more" on the Customers screen (the QA app has its own data; each test adds 33 customers of its own). */
class CustomerPaginationTest {
    val compose=createAndroidComposeRule<MainActivity>()
    private val guest=object:ExternalResource() {
        override fun before() {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            SessionStore(context).save(Session("local-device","","","",Long.MAX_VALUE,true))
            // A fresh install shows the introduction first; these tests start from the app itself.
            kotlinx.coroutines.runBlocking {(context.applicationContext as RaheelApp).onboarding.complete()}
        }
    }
    @get:Rule val rules:RuleChain=RuleChain.outerRule(guest).around(compose)

    @Test fun customersAppendTwentyFivePerLoadMoreAndResetOnSearch() {
        lateinit var model:OrdersViewModel
        compose.runOnUiThread {model=ViewModelProvider(compose.activity)[OrdersViewModel::class.java]}
        compose.waitUntil(40000) {!model.state.value.loading && !model.state.value.busy}
        addCustomers()
        val total=runBlocking {(compose.activity.application as RaheelApp).workspace()!!.customerDirectory(CustomerDirectoryQuery(loaded=100)).size}
        assertTrue("more than one page of customers ($total)",total>ListRules.PageSize+5)

        compose.runOnUiThread {model.updateCustomers(CustomerDirectoryQuery())}
        compose.waitUntil(10000) {model.state.value.customers.size==ListRules.PageSize}
        assertTrue(model.state.value.customersMore)
        val firstPage=model.state.value.customers.map {it.latestOrder.normalizedMobile}

        compose.runOnUiThread {model.updateCustomers(CustomerDirectoryQuery(loaded=2))}
        compose.waitUntil(10000) {model.state.value.customers.size>ListRules.PageSize}
        assertEquals("earlier rows keep their order",firstPage,model.state.value.customers.take(ListRules.PageSize).map {it.latestOrder.normalizedMobile})
        assertEquals("no duplicates after appending",model.state.value.customers.size,model.state.value.customers.map {it.latestOrder.normalizedMobile}.toSet().size)
        assertEquals(minOf(total,2*ListRules.PageSize),model.state.value.customers.size)
        assertEquals(total>2*ListRules.PageSize,model.state.value.customersMore)

        // A new search starts again from a single page.
        compose.runOnUiThread {model.updateCustomers(CustomerDirectoryQuery(search="Paging"))}
        compose.waitUntil(10000) {model.state.value.customers.size==ListRules.PageSize}
        assertTrue(model.state.value.customersMore)
    }

    @Test fun loadMoreButtonOnTheCustomersScreenShowsTheNextPage() {
        lateinit var model:OrdersViewModel
        compose.runOnUiThread {model=ViewModelProvider(compose.activity)[OrdersViewModel::class.java]}
        compose.waitUntil(40000) {!model.state.value.loading && !model.state.value.busy}
        addCustomers()
        compose.onNodeWithContentDescription("Customers").performClick()
        compose.waitUntil(40000) {model.state.value.customers.size==ListRules.PageSize}
        // The footer is below 25 cards in a lazy list, so scroll to it before pressing it.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Load more customers"))
        compose.onNodeWithText("Load more customers").performClick()
        compose.waitUntil(10000) {model.state.value.customers.size>ListRules.PageSize}
        assertTrue(model.state.value.customers.size>ListRules.PageSize)
    }

    /** 33 customers with deterministic ids and mobiles, so running twice never duplicates them. */
    private fun addCustomers() {
        runBlocking {
            val store=(compose.activity.application as RaheelApp).workspace()!!
            for(c in 1..33) {
                val id=java.util.UUID.nameUUIDFromBytes("${store.accountId}:paging-order:$c".toByteArray()).toString()
                if(store.hasTaskId(id)) continue
                val mobile="0300"+(2_000_000+c)
                store.save(Order(id,"Paging Customer $c",customerMobile=mobile,normalizedMobile=PakistanPhone.normalize(mobile)!!,
                    orderNumber="RO-PAGE-%02d".format(c),amounts=OrderAmounts(framePaisa=300000)),"create",null)
            }
        }
    }
}
