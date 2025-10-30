package com.jagrosh.jmusicbot.utils;

import com.sedmelluq.lava.extensions.youtuberotator.planner.*;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.IpBlock;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.Ipv4Block;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.Ipv6Block;

import java.util.List;

public class YouTubeUtil {
    public enum RoutingPlanner {
        NONE,
        ROTATE_ON_BAN,
        LOAD_BALANCE,
        NANO_SWITCH,
        ROTATING_NANO_SWITCH
    }

    public static IpBlock parseIpBlock(String cidr) {
        if (Ipv6Block.isIpv6CidrBlock(cidr))
            return new Ipv6Block(cidr);

        if (Ipv4Block.isIpv4CidrBlock(cidr))
            return new Ipv4Block(cidr);

        throw new IllegalArgumentException("Could not parse CIDR " + cidr);
    }

    public static AbstractRoutePlanner createRouterPlanner(RoutingPlanner routingPlanner, List<IpBlock> ipBlocks) {

        switch (routingPlanner) {
            case NONE:
                return null;
            case ROTATE_ON_BAN:
                return new RotatingIpRoutePlanner(ipBlocks);
            case LOAD_BALANCE:
                return new BalancingIpRoutePlanner(ipBlocks);
            case NANO_SWITCH:
                return new NanoIpRoutePlanner(ipBlocks, true);
            case ROTATING_NANO_SWITCH:
                return new RotatingNanoIpRoutePlanner(ipBlocks);
            default:
                throw new IllegalArgumentException("Unknown RoutingPlanner value provided");
        }
    }
}