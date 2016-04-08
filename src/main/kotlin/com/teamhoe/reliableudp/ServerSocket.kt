package com.teamhoe.reliableudp

import com.teamhoe.reliableudp.net.*
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Created by Eric on 4/7/2016.
 */
class ServerSocket(private val port:Int? = null)
{
    companion object
    {
        const val NUM_SYN_TO_SEND_FOR_CONNECT = 5
        const val DEFAULT_WINDOW_SIZE = Int.MAX_VALUE
    }

    internal val udpSocket = port?.let {DatagramSocket(port)} ?: DatagramSocket()

    internal val ackReceivers:MutableMap<SocketAddress,AckReceiver> = LinkedHashMap()

    internal val seqReceivers:MutableMap<SocketAddress,SeqReceiver> = LinkedHashMap()

    private val pendingAccepts:Queue<SeqReceiver> = LinkedBlockingQueue()

    /**
     * blocks until a connection request was received, and accepted. returns a
     * [SocketInputStream].
     */
    fun accept(timeout:Long?):SocketInputStream
    {
        val seqReceiver = AcceptRequestSeqReceiverAdapter(AcceptRequest())
        val acceptRequest = seqReceiver.wrapee

        // registers input stream into queue of input streams pending to accept
        // new connection requests
        pendingAccepts.add(seqReceiver)

        // await acceptance of connection request
        acceptRequest.awaitEstablished(timeout)

        // once connect request is received, remove accept request from pending
        // collection, and register a socket input stream in its place
        val inputStream = SocketInputStream(this,acceptRequest.syn!!.remoteAddress,acceptRequest.syn!!.sequenceNumber,DEFAULT_WINDOW_SIZE)
        inputStream.receive(acceptRequest.syn!!)
        synchronized(seqReceivers)
        {
            seqReceivers[acceptRequest.syn!!.remoteAddress] = SocketInputStreamSeqReceiverAdapter(inputStream)
            pendingAccepts.remove(seqReceiver)
        }
        return inputStream
    }

    /**
     * blocks until the connection request sent to the [remoteAddress] is
     * accepted. returns a [SocketOutputStream]. throws an
     * [IllegalArgumentException] if there is still an ongoing connection to
     * [remoteAddress]. throws [ConnectException] if the connection request
     * times out.
     */
    fun connect(remoteAddress:SocketAddress,timeout:Long):SocketOutputStream
    {
        // check for conflicting connection then register request to make sure
        // there is only one output stream to each remote address from this
        // socket
        val connectRequest = synchronized(ackReceivers)
        {
            if (ackReceivers[remoteAddress] != null)
            {
                throw IllegalArgumentException("conflicting ongoing connection present")
            }
            else
            {
                val connectRequest = ConnectRequest(remoteAddress,udpSocket)
                ackReceivers[remoteAddress] = ConnectRequestAckReceiverAdapter(connectRequest)
                return@synchronized connectRequest
            }
        }

        // send connection request and await timeout or acceptance
        connectRequest.connect(timeout)

        // if connection was unsuccessful, unregister entry from ack receivers
        if (!connectRequest.isConnected)
        {
            synchronized(ackReceivers) {ackReceivers.remove(remoteAddress)}
            throw ConnectException("connection request timed out")
        }

        // replace ack receiver with output stream otherwise
        else
        {
            val outputStream = SocketOutputStream(/*todo*/)
            synchronized(ackReceivers) {ackReceivers[remoteAddress] = SocketOutputStreamAckReceiverAdapter(outputStream)}
            return outputStream
        }
    }

    private val receiveThread = thread(isDaemon = true,name = "$this.receiveThread")
    {
        val datagram = DatagramPacket(ByteArray(NetUtils.MAX_IP_PACKET_LEN),NetUtils.MAX_IP_PACKET_LEN)
        while (!udpSocket.isClosed)
        {
            // receive and parse next datagram
            udpSocket.receive(datagram)
            val packet = Packet.parse(datagram)

            // handle packet based on type
            when (packet)
            {
                // let sequenced packets be handled by input streams
                is ISeqPacket ->
                {
                    (seqReceivers[packet.remoteAddress] ?: pendingAccepts.firstOrNull())?.receive(packet)
                }

                // let acknowledgement packets be handled by output streams
                is IAckPacket ->
                {
                    ackReceivers[packet.remoteAddress]?.receive(packet)
                }
            }
        }
    }
}

private class ConnectRequest(val remoteAddress:SocketAddress,val udpSocket:DatagramSocket)
{
    val initialSequenceNumber = (Math.random()*Int.MAX_VALUE).toInt()

    var isConnected = false
        private set

    private val releasedOnEstablished = CountDownLatch(1)

    fun connect(timeout:Long?) = synchronized(this)
    {
        // verify virgin status
        assert(releasedOnEstablished.count > 0)

        // send syn packets
        repeat(ServerSocket.NUM_SYN_TO_SEND_FOR_CONNECT)
        {
            val syn = SynPacket(remoteAddress,initialSequenceNumber)
            udpSocket.send(syn.datagram)
        }

        // await response with timeout
        if (timeout != null)
        {
            releasedOnEstablished.await(timeout,TimeUnit.MILLISECONDS)
        }
        else
        {
            releasedOnEstablished.await()
        }
    }

    fun receive(ackPacket:IAckPacket)
    {
        if (ackPacket.remoteAddress == remoteAddress &&
            ackPacket.acknowledgementNumber == initialSequenceNumber)
        {
            isConnected = true
            releasedOnEstablished.countDown()
        }
    }
}

internal interface AckReceiver
{
    fun receive(packet:IAckPacket)
}

private class SocketOutputStreamAckReceiverAdapter(val wrapee:SocketOutputStream):AckReceiver
{
    override fun receive(packet:IAckPacket) = wrapee.receive(packet)
}

private class ConnectRequestAckReceiverAdapter(val wrapee:ConnectRequest):AckReceiver
{
    override fun receive(packet:IAckPacket) = wrapee.receive(packet)
}

private class AcceptRequest()
{
    var syn:SynPacket? = null
        private set

    private val releasedOnEstablished = CountDownLatch(1)

    fun awaitEstablished(timeout:Long?) = synchronized(this)
    {
        // verify virgin status
        assert(isVirgin())

        // await response with timeout
        if (timeout != null)
        {
            releasedOnEstablished.await(timeout,TimeUnit.MILLISECONDS)
        }
        else
        {
            releasedOnEstablished.await()
        }
    }

    fun receive(seqPacket:ISeqPacket)
    {
        if (isVirgin() && seqPacket is SynPacket)
        {
            syn = seqPacket
            releasedOnEstablished.countDown()
        }
    }

    private fun isVirgin():Boolean = releasedOnEstablished.count > 0
}

internal interface SeqReceiver
{
    fun receive(packet:ISeqPacket)
}

private class AcceptRequestSeqReceiverAdapter(val wrapee:AcceptRequest):SeqReceiver
{
    override fun receive(packet:ISeqPacket) = wrapee.receive(packet)
}

private class SocketInputStreamSeqReceiverAdapter(val wrapee:SocketInputStream):SeqReceiver
{
    override fun receive(packet:ISeqPacket) = wrapee.receive(packet)
}