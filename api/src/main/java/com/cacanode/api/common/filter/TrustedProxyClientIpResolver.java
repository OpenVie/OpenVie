package com.cacanode.api.common.filter;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

@Component
public class TrustedProxyClientIpResolver {
    private final List<Cidr> trusted;

    public TrustedProxyClientIpResolver(@Value("${app.security.trusted-proxy-cidrs:127.0.0.1/32,::1/128}") String cidrs) {
        List<Cidr> values=new ArrayList<>();
        for(String value:cidrs.split(","))if(!value.isBlank())values.add(Cidr.parse(value.strip()));
        trusted=List.copyOf(values);
    }

    public String resolve(HttpServletRequest request) {
        InetAddress peer=parse(request.getRemoteAddr());
        if(peer==null||!isTrusted(peer))return peer==null?request.getRemoteAddr():peer.getHostAddress();
        String forwarded=request.getHeader("X-Forwarded-For");
        if(forwarded==null||forwarded.isBlank())return peer.getHostAddress();
        String[] chain=forwarded.split(",");
        for(int index=chain.length-1;index>=0;index--) {
            InetAddress address=parse(chain[index].strip());
            if(address==null)return peer.getHostAddress();
            if(!isTrusted(address))return address.getHostAddress();
        }
        return peer.getHostAddress();
    }

    private boolean isTrusted(InetAddress address){return trusted.stream().anyMatch(cidr->cidr.contains(address));}
    private static InetAddress parse(String value){
        if(value==null||value.isBlank()||value.contains("%")
                ||(!value.matches("[0-9.]+")&&!value.matches("[0-9a-fA-F:]+")))return null;
        try{return InetAddress.getByName(value);}catch(Exception ignored){return null;}
    }

    record Cidr(byte[] network,int prefix) {
        static Cidr parse(String value) {
            try {
                String[] parts=value.split("/",-1);InetAddress address=TrustedProxyClientIpResolver.parse(parts[0]);
                if(address==null)throw new IllegalArgumentException("CIDR address must be numeric");
                int bits=address.getAddress().length*8;int prefix=parts.length==2?Integer.parseInt(parts[1]):bits;
                if(prefix<0||prefix>bits)throw new IllegalArgumentException("Invalid trusted proxy CIDR");
                byte[] network=address.getAddress().clone();mask(network,prefix);return new Cidr(network,prefix);
            } catch(Exception exception){throw new IllegalArgumentException("Invalid trusted proxy CIDR: "+value,exception);}
        }
        boolean contains(InetAddress value){byte[] candidate=value.getAddress().clone();if(candidate.length!=network.length)return false;mask(candidate,prefix);return java.util.Arrays.equals(network,candidate);}
        private static void mask(byte[] value,int prefix){for(int bit=prefix;bit<value.length*8;bit++)value[bit/8]&=(byte)~(1<<(7-bit%8));}
    }
}
