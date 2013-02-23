package water;
import java.net.InetAddress;
import java.net.UnknownHostException;

import sun.misc.Unsafe;
import water.nbhm.UtilUnsafe;

/**

 * Maintain a VERY efficient list of events in the system.  This must be VERY
 * cheap to call, as it will get called alot.  On demand, we can snapshot this
 * list gather all other lists from all other (responsive) Nodes, and build a
 * whole-Cloud timeline for dumping.
 *
 *
 * @author <a href="mailto:cliffc@0xdata.com"></a>
 * @version 1.0
 */

public class TimeLine extends UDP {
  private static final Unsafe _unsafe = UtilUnsafe.getUnsafe();

  // The TimeLine buffer.

  // The TimeLine buffer is full of Events; each event has a timestamp and some
  // event bytes.  The buffer is a classic ring buffer; we toss away older
  // events.  We snapshot the buffer by replacing it with a fresh array.  The
  // index of the next free slot is kept in the 1st long of the array, and
  // there are MAX_EVENTS (a power of 2) more slots.

  // A TimeLine event is:
  // - Milliseconds since JVM boot; 4 bytes
  // - IP4 of send/recv
  // - Sys.Nano, 8 bytes -1 bit
  // - Nano low bit is 0 for send, 1 for recv
  // - 16 bytes of payload; 1st byte is a udp_type opcode, next 4 bytes are typically task#
  static final int MAX_EVENTS=1024; // Power-of-2, please
  static final int WORDS_PER_EVENT=4;
  static final long[] TIMELINE = new long[MAX_EVENTS*WORDS_PER_EVENT+1];

  static long JVM_BOOT_MSEC = System.currentTimeMillis();


  // Snapshot and return the current TIMELINE array
  public static long[] snapshot() { return TIMELINE.clone(); }

  // CAS access to the TIMELINE array
  private static final int _Lbase  = _unsafe.arrayBaseOffset(long[].class);
  private static final int _Lscale = _unsafe.arrayIndexScale(long[].class);
  private static long rawIndex(long[] ary, int i) {
    assert i >= 0 && i < ary.length;
    return _Lbase + i * _Lscale;
  }
  private final static boolean CAS( long[] A, int idx, long old, long nnn ) {
    return _unsafe.compareAndSwapLong( A, rawIndex(A,idx), old, nnn );
  }
  // Return the next index into the TIMELINE array
  private final static int next_idx( long [] tl ) {
    // Spin until we can CAS-acquire a fresh index
    while( true ) {
      int oldidx = (int)tl[0];
      int newidx = (oldidx+1)&(MAX_EVENTS-1);
      if( CAS( tl, 0, oldidx, newidx ) )
        return oldidx;
    }
  }

  // Record 1 event, the first 16 bytes of this buffer.  This is expected to be
  // a high-volume multi-thread operation so needs to be fast.  "sr" is send-
  // receive and must be either 0 or 1.  "drop" is whether or not the UDP
  // packet is dropped as-if a network drop, and must be either 0 (kept) or 2
  // (dropped).
  private static void record( AutoBuffer b, int sr, int drop ) {
    final long ms = System.currentTimeMillis(); // Read first, in case we're slow storing values
    final long ns = System.nanoTime();
    final long[] tl = TIMELINE; // Read once, in case the whole array shifts out from under us
    final int idx = next_idx(tl); // Next free index
    long deltams = ms-JVM_BOOT_MSEC;
    assert deltams < 0x0FFFFFFFFL; // No daily overflow
    if( b.position() < 16 ) b.position(16);
    tl[idx*WORDS_PER_EVENT+0+1] = (deltams)<<32 | (b._h2o.ip4()&0x0FFFFFFFFL);
    tl[idx*WORDS_PER_EVENT+1+1] = (ns&~3)|sr|drop;
    tl[idx*WORDS_PER_EVENT+2+1] = b.get8(0);
    tl[idx*WORDS_PER_EVENT+3+1] = b.get8(8);
  }
  public static void record_send( AutoBuffer b )           { record(b,0,0); }
  public static void record_recv( AutoBuffer b, int drop ) { record(b,1,drop); }

  // Accessors, for TimeLines that come from all over the system
  public static int length( ) { return MAX_EVENTS; }
  // Internal array math so we can keep layout private
  private static int idx(long[] tl, int i ) { return (((int)tl[0]+i)&(MAX_EVENTS-1))*WORDS_PER_EVENT+1; }
  // That first long is complex: compressed CTM and IP4
  private static long x0( long[] tl, int idx ) { return tl[idx(tl,idx)+0]; }
  public static long ms( long[] tl, int idx ) { return (x0(tl,idx)>>>32)+JVM_BOOT_MSEC; }
  public static InetAddress inet( long[] tl, int idx ) {
    int adr = (int)x0(tl,idx);
    byte[] ip4 = new byte[4];
    ip4[0] = (byte)(adr>> 0);
    ip4[1] = (byte)(adr>> 8);
    ip4[2] = (byte)(adr>>16);
    ip4[3] = (byte)(adr>>24);
    try { return InetAddress.getByAddress(ip4); }
    catch( UnknownHostException e ) { }
    return null;
  }
  // That 2nd long is nanosec, plus the low bit is send/recv & 2nd low is drop
  public static long ns( long[] tl, int idx ) { return tl[idx(tl,idx)+1]; }
  // Returns zero for send, 1 for recv
  public static int send_recv( long[] tl, int idx ) { return (int)(ns(tl,idx)&1); }
  // Returns zero for kept, 2 for dropped
  public static int dropped  ( long[] tl, int idx ) { return (int)(ns(tl,idx)&2); }
  // 16 bytes of payload
  public static long l0( long[] tl, int idx ) { return tl[idx(tl,idx)+2]; }
  public static long l8( long[] tl, int idx ) { return tl[idx(tl,idx)+3]; }

  public static boolean isEmpty( long[] tl, int idx ) { return tl[idx(tl,idx)+0]==0; }

  // Take a system-wide snapshot.  Return an array, indexed by H2ONode _idx,
  // containing that Node's snapshot.  Try to get all the snapshots as close as
  // possible to the same point in time.
  static long[][] SNAPSHOT;
  static long TIME_LAST_SNAPSHOT = 1;
  static public H2O CLOUD;      // Cloud instance being snapshotted
  static public long[][] system_snapshot() {
    // Now spin-wait until we see all snapshots check in.
    // Be atomic about it.
    synchronized( TimeLine.class ) {
      // First see if we have a recent snapshot already.
      long now = System.currentTimeMillis();
      if( now - TIME_LAST_SNAPSHOT < 3*1000 )
        return SNAPSHOT;        // Use the recent snapshot

      // A new snapshot is being built?
      if( TIME_LAST_SNAPSHOT != 0 ) {
        TIME_LAST_SNAPSHOT = 0; // Only fire off the UDP packet once; flag it
        // Make a new empty snapshot
        CLOUD = H2O.CLOUD;
        SNAPSHOT = new long[CLOUD.size()][];
        // Broadcast a UDP packet, with the hopes of getting all SnapShots as close
        // as possible to the same point in time.
        new AutoBuffer(H2O.SELF).putUdp(udp.timeline).close();
      }
      // Spin until all snapshots appear
      while( true ) {
        boolean done = true;
        for( int i=0; i<CLOUD._memary.length; i++ )
          if( SNAPSHOT[i] == null )
            done = false;
        if( done ) break;
        try { TimeLine.class.wait(); } catch( InterruptedException e ) {}
      }
      TIME_LAST_SNAPSHOT = System.currentTimeMillis();
      return SNAPSHOT;
    }
  }

  // Send our most recent timeline to the remote via TCP
  public AutoBuffer call( AutoBuffer ab ) {
    long[] a = snapshot();
    if( ab._h2o == H2O.SELF ) {
      synchronized(TimeLine.class) {
        for( int i=0; i<CLOUD._memary.length; i++ )
          if( CLOUD._memary[i]==H2O.SELF )
            SNAPSHOT[i] = a;
        TimeLine.class.notify();
      }
      return ab; // No I/O needed for my own snapshot
    }
    // Send timeline to remote
    return ab.clearForWriting().putUdp(UDP.udp.timeline).putA8(a);
  }

  // Receive a remote timeline
  static void tcp_call( final AutoBuffer ab ) {
    int port = ab.getPort();
    long[] snap = ab.getA8();
    int idx = CLOUD.nidx(ab._h2o);
    if( idx >= 0 && idx < SNAPSHOT.length )
      SNAPSHOT[idx] = snap;     // Ignore out-of-cloud timelines
    ab.close();
    synchronized(TimeLine.class) {  TimeLine.class.notify();  }
  }

  public String print16( AutoBuffer ab ) { return ""; } // no extra info in a timeline packet
}
