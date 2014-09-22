package io.netflow.actors

import java.net.{InetAddress, InetSocketAddress}

import com.websudos.phantom.Implicits._
import io.netflow.flows._
import io.netflow.lib._
import io.netflow.timeseries._
import io.wasted.util._
import org.joda.time.DateTime

private case class BadDatagram(date: DateTime, sender: InetAddress)

private case class SaveJob(
  sender: InetSocketAddress,
  flowPacket: FlowPacket,
  prefixes: List[InetPrefix],
  thruputPrefixes: List[InetPrefix])

private[netflow] class FlowWorker(num: Int) extends Wactor {
  override val loggerName = "FlowWorker %02d:".format(num)

  def receive = {
    case BadDatagram(date, sender) =>
    // FIXME count bad datagrams

    case SaveJob(sender, flowPacket, prefixes, thruputPrefixes) =>
      var batch = new BatchStatement()

      /* Filters to get a list of prefixes that match */
      def findNetworks(flowAddr: InetAddress) = prefixes.filter(_.contains(flowAddr))
      def findThruputNetworks(flow: NetFlowData[_]) =
        thruputPrefixes.filter(x => x.contains(flow.srcAddress) || x.contains(flow.dstAddress))

      flowPacket.flows foreach {
        case tmpl: cflow.Template =>
        // FIXME maybe add thruput notification
        case flow: NetFlowData[_] =>
          var ourFlow = false

          val srcNetworks = findNetworks(flow.srcAddress)
          val dstNetworks = findNetworks(flow.dstAddress)

          // src - out
          srcNetworks foreach { prefix =>
            ourFlow = true
            // If it is *NOT* *to* another network we monitor
            val trafficType = if (dstNetworks.isEmpty) TrafficType.Outbound else TrafficType.OutboundLocal
            batch = add(batch, flowPacket, flow, flow.srcAddress, trafficType, prefix)
          }

          // dst - in
          dstNetworks foreach { prefix =>
            ourFlow = true
            // If it is *NOT* *to* another network we monitor
            val trafficType = if (srcNetworks.isEmpty) TrafficType.Inbound else TrafficType.InboundLocal
            batch = add(batch, flowPacket, flow, flow.dstAddress, trafficType, prefix)
          }

          // thruput
          findThruputNetworks(flow) foreach { prefix =>
            //thruput(sender, flow, prefix, flow.dstAddress)
          }

          if (!ourFlow) debug("Ignoring Flow: %s", flow)
        case _ =>
      }

      // execute the batch
      batch.future()

      val flowSeq = flowPacket match {
        case a: cflow.NetFlowV5Packet => ", flowSeq: " + a.flowSequence
        case a: cflow.NetFlowV6Packet => ", flowSeq: " + a.flowSequence
        case a: cflow.NetFlowV7Packet => ", flowSeq: " + a.flowSequence
        case a: cflow.NetFlowV9Packet => ", flowSeq: " + a.flowSequence
        //case a: cflow.NetFlowV10Packet => ", flowSeq: " + a.flowSequence // FIXME netflow 10
        case _ => ""
      }

      val packetInfoStr = flowPacket.version.replaceAll("Packet", "-") + " length: " + flowPacket.length + flowSeq
      val passedFlowsStr = flowPacket.flows.length + "/" + flowPacket.count + " passed"

      val recvdFlows = flowPacket.flows.groupBy(_.version)
      val recvdFlowsStr = recvdFlows.toList.sortBy(_._1).map { fc =>
        if (fc._2.length == 1) fc._1 else fc._1 + ": " + fc._2.length
      }.mkString(", ")

      // log an elaborate string to loglevel info describing this packet.
      // Warning: can produce huge amounts of logs if written to disk.
      val debugStr = "\t" + packetInfoStr + "\t" + passedFlowsStr + "\t" + recvdFlowsStr

      // Sophisticated log-level hacking :<
      if (flowPacket.count != flowPacket.flows.length) error(debugStr)
      else if (debugStr.contains("Template")) info(debugStr) else debug(debugStr)

      // save this record
      NetFlowStats.insert
        .value(_.id, flowPacket.id)
        .value(_.date, DateTime.now)
        .value(_.sender, sender.getAddress)
        .value(_.port, sender.getPort)
        .value(_.version, flowPacket.version)
        .value(_.flows, flowPacket.flows.length)
        .value(_.bytes, flowPacket.length).future()
  }

  // Handle NetFlowData
  def add(batch: BatchStatement, flowPacket: FlowPacket, flow: NetFlowData[_], localAddress: InetAddress,
          direction: TrafficType.Value, prefix: InetPrefix): BatchStatement = {
    val date = flowPacket.timestamp
    val year = date.getYear.toString
    val month = "%02d".format(date.getMonthOfYear)
    val day = "%02d".format(date.getDayOfMonth)
    val hour = "%02d".format(date.getHourOfDay)
    val minute = "%02d".format(date.getMinuteOfHour)
    val pfx = prefix.prefix.getHostAddress
    val keys = List[String](
      year,
      year + "/" + month,
      year + "/" + month + "/" + day,
      year + "/" + month + "/" + day + "-" + hour,
      year + "/" + month + "/" + day + "-" + hour + ":" + minute)

    //get/10.4.20.5/10.4.20.0/24/2001:4cea::/32  { years: [2014,2013,2012,2011], month: [201401, 201402, 201403] }

    var editBatch = batch
    keys.foreach { key =>

      // all counters
      editBatch = editBatch.add(NetFlowSeries.update
        .where(_.sender eqs flowPacket.sender.getAddress)
        .and(_.prefix eqs pfx + "/" + prefix.prefixLen)
        .and(_.date eqs key)
        .and(_.name eqs "all")
        .and(_.direction eqs direction.toString)
        .modify(_.bytes increment flow.bytes)
        .and(_.pkts increment flow.pkts))

      // proto counters
      editBatch = editBatch.add(NetFlowSeries.update
        .where(_.sender eqs flowPacket.sender.getAddress)
        .and(_.prefix eqs pfx + "/" + prefix.prefixLen)
        .and(_.date eqs key)
        .and(_.name eqs "proto:" + flow.proto)
        .and(_.direction eqs direction.toString)
        .modify(_.bytes increment flow.bytes)
        .and(_.pkts increment flow.pkts))

      // src-port counters
      editBatch = editBatch.add(NetFlowSeries.update
        .where(_.sender eqs flowPacket.sender.getAddress)
        .and(_.prefix eqs pfx + "/" + prefix.prefixLen)
        .and(_.date eqs key)
        .and(_.name eqs "srcport:" + flow.srcPort)
        .and(_.direction eqs direction.toString)
        .modify(_.bytes increment flow.bytes)
        .and(_.pkts increment flow.pkts))

      // dst-port counters
      editBatch = editBatch.add(NetFlowSeries.update
        .where(_.sender eqs flowPacket.sender.getAddress)
        .and(_.prefix eqs pfx + "/" + prefix.prefixLen)
        .and(_.date eqs key)
        .and(_.name eqs "dstport:" + flow.dstPort)
        .and(_.direction eqs direction.toString)
        .modify(_.bytes increment flow.bytes)
        .and(_.pkts increment flow.pkts))

      // src-as counters
      editBatch = editBatch.add(NetFlowSeries.update
        .where(_.sender eqs flowPacket.sender.getAddress)
        .and(_.prefix eqs pfx + "/" + prefix.prefixLen)
        .and(_.date eqs key)
        .and(_.name eqs "srcas:" + flow.srcAS)
        .and(_.direction eqs direction.toString)
        .modify(_.bytes increment flow.bytes)
        .and(_.pkts increment flow.pkts))

      // dst-as counters
      editBatch = editBatch.add(NetFlowSeries.update
        .where(_.sender eqs flowPacket.sender.getAddress)
        .and(_.prefix eqs pfx + "/" + prefix.prefixLen)
        .and(_.date eqs key)
        .and(_.name eqs "dstas:" + flow.dstAS)
        .and(_.direction eqs direction.toString)
        .modify(_.bytes increment flow.bytes)
        .and(_.pkts increment flow.pkts))

      // src-ip counters
      editBatch = editBatch.add(NetFlowSeries.update
        .where(_.sender eqs flowPacket.sender.getAddress)
        .and(_.prefix eqs pfx + "/" + prefix.prefixLen)
        .and(_.date eqs key)
        .and(_.name eqs "srcip:" + flow.srcAddress.getHostAddress)
        .and(_.direction eqs direction.toString)
        .modify(_.bytes increment flow.bytes)
        .and(_.pkts increment flow.pkts))

      // dst-port counters
      editBatch = editBatch.add(NetFlowSeries.update
        .where(_.sender eqs flowPacket.sender.getAddress)
        .and(_.prefix eqs pfx + "/" + prefix.prefixLen)
        .and(_.date eqs key)
        .and(_.name eqs "dstip:" + flow.dstAddress.getHostAddress)
        .and(_.direction eqs direction.toString)
        .modify(_.bytes increment flow.bytes)
        .and(_.pkts increment flow.pkts))
    }
    editBatch
  }
}
