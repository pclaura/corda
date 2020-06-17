package net.corda.node.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.CordaRuntimeException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.UnexpectedFlowEndException
import net.corda.core.identity.Party
import net.corda.core.internal.concurrent.transpose
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.seconds
import net.corda.core.utilities.toNonEmptySet
import net.corda.core.utilities.unwrap
import net.corda.node.services.Permissions
import net.corda.node.services.statemachine.transitions.PrematureSessionClose
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import net.corda.testing.node.internal.enclosedCordapp
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import kotlin.test.assertEquals

class FlowSessionCloseTest {

    private val user = User("user", "pwd", setOf(Permissions.all()))

    @Test(timeout=300_000)
    fun `flow cannot close uninitialised session`() {
        driver(DriverParameters(startNodesInProcess = true, cordappsForAllNodes = listOf(enclosedCordapp()), notarySpecs = emptyList())) {
            val (nodeAHandle, nodeBHandle) = listOf(
                    startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)),
                    startNode(providedName = BOB_NAME, rpcUsers = listOf(user))
            ).transpose().getOrThrow()

            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                assertThatThrownBy { it.proxy.startFlow(::InitiatorFlow, nodeBHandle.nodeInfo.legalIdentities.first(), true, false).returnValue.getOrThrow() }
                        .isInstanceOf(CordaRuntimeException::class.java)
                        .hasMessageContaining(PrematureSessionClose::class.java.name)
                        .hasMessageContaining("The following session was closed before it was initialised")
            }
        }
    }

    @Test(timeout=300_000)
    fun `flow cannot access closed session`() {
        driver(DriverParameters(startNodesInProcess = true, cordappsForAllNodes = listOf(enclosedCordapp()), notarySpecs = emptyList())) {
            val (nodeAHandle, nodeBHandle) = listOf(
                    startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)),
                    startNode(providedName = BOB_NAME, rpcUsers = listOf(user))
            ).transpose().getOrThrow()

            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                assertThatThrownBy { it.proxy.startFlow(::InitiatorFlow, nodeBHandle.nodeInfo.legalIdentities.first(), false, true).returnValue.getOrThrow() }
                        .isInstanceOf(UnexpectedFlowEndException::class.java)
                        .hasMessageContaining("Tried to access ended session")
            }
        }
    }

    @Test(timeout=300_000)
    fun `flow can close initialised session successfully`() {
        driver(DriverParameters(startNodesInProcess = true, cordappsForAllNodes = listOf(enclosedCordapp()), notarySpecs = emptyList())) {
            val (nodeAHandle, nodeBHandle) = listOf(
                    startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)),
                    startNode(providedName = BOB_NAME, rpcUsers = listOf(user))
            ).transpose().getOrThrow()

            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                it.proxy.startFlow(::InitiatorFlow, nodeBHandle.nodeInfo.legalIdentities.first(), false, false).returnValue.getOrThrow()
            }
        }
    }

    @Test(timeout=300_000)
    fun `flow can close multiple sessions successfully`() {
        driver(DriverParameters(startNodesInProcess = true, cordappsForAllNodes = listOf(enclosedCordapp()), notarySpecs = emptyList())) {
            val (nodeAHandle, nodeBHandle) = listOf(
                    startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)),
                    startNode(providedName = BOB_NAME, rpcUsers = listOf(user))
            ).transpose().getOrThrow()

            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                it.proxy.startFlow(::InitiatorMultipleSessionsFlow, nodeBHandle.nodeInfo.legalIdentities.first()).returnValue.getOrThrow()
            }
        }
    }

    /**
     * This test ensures that when sessions are closed, the associated resources are eagerly cleaned up.
     * If sessions are not closed, then the node will crash with an out-of-memory error.
     * This can be confirmed by commenting out [FlowSession.close] operation in the invoked flow and re-run the test.
     */
    @Test(timeout=300_000)
    fun `flow looping over sessions can close them to release resources and avoid out-of-memory failures`() {
        driver(DriverParameters(startNodesInProcess = false, cordappsForAllNodes = listOf(enclosedCordapp()), notarySpecs = emptyList())) {
            val (nodeAHandle, nodeBHandle) = listOf(
                    startNode(providedName = ALICE_NAME, rpcUsers = listOf(user), maximumHeapSize = "256m"),
                    startNode(providedName = BOB_NAME, rpcUsers = listOf(user), maximumHeapSize = "256m")
            ).transpose().getOrThrow()

            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                it.proxy.startFlow(::InitiatorLoopingFlow, nodeBHandle.nodeInfo.legalIdentities.first()).returnValue.getOrThrow()
            }
        }
    }

    /* test about get flow info */

    @InitiatingFlow
    @StartableByRPC
    class InitiatorFlow(val party: Party, private val prematureClose: Boolean = false, private val accessClosedSession: Boolean = false): FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val session = initiateFlow(party)

            if (prematureClose) {
                session.close()
            }

            session.send("Hello")

            if (accessClosedSession) {
                sleep(1.seconds)
                session.receive<String>()
            }
        }
    }

    @InitiatedBy(InitiatorFlow::class)
    class InitiatedFlow(private val otherSideSession: FlowSession): FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            otherSideSession.receive<String>()
                    .unwrap{ assertEquals("Hello", it) }

            otherSideSession.close()
        }
    }

    @InitiatingFlow
    @StartableByRPC
    class InitiatorLoopingFlow(val party: Party): FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            for (i in 1..1_000) {
                val session = initiateFlow(party)
                session.sendAndReceive<String>("What's up?").unwrap{ assertEquals("All good!", it) }

                session.close()

                logger.info("Completed iteration $i")
            }
        }
    }

    @InitiatedBy(InitiatorLoopingFlow::class)
    class InitiatedLoopingFlow(private val otherSideSession: FlowSession): FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            otherSideSession.receive<String>()
                    .unwrap{ assertEquals("What's up?", it) }
            otherSideSession.send("All good!")
        }
    }

    @InitiatingFlow
    @StartableByRPC
    class InitiatorMultipleSessionsFlow(val party: Party): FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            for (round in 1 .. 2) {
                val sessions = mutableListOf<FlowSession>()
                for (session_number in 1 .. 5) {
                    val session = initiateFlow(party)
                    sessions.add(session)
                    session.sendAndReceive<String>("What's up?").unwrap{ assertEquals("All good!", it) }
                }
                close(sessions.toNonEmptySet())
            }
        }
    }

    @InitiatedBy(InitiatorMultipleSessionsFlow::class)
    class InitiatedMultipleSessionsFlow(private val otherSideSession: FlowSession): FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            otherSideSession.receive<String>()
                    .unwrap{ assertEquals("What's up?", it) }
            otherSideSession.send("All good!")
        }
    }

}