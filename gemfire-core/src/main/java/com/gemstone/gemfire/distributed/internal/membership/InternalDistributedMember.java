/*=========================================================================
 * Copyright (c) 2002-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package com.gemstone.gemfire.distributed.internal.membership;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.UnsupportedVersionException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DurableClientAttributes;
import com.gemstone.gemfire.distributed.Role;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor.ProfileId;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.Version;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * This is the fundamental representation of a member of a GemFire distributed
 * system.
 *
 * Unfortunately, this class serves two distinct functions. First, it is the
 * fundamental element of membership in the GemFire distributed system. As such,
 * it is used in enumerations and properly responds to hashing and equals()
 * comparisons.
 *
 * Second, it is used as a cheap way of representing an address. This is
 * unfortunate, because as a NetMember, it holds two separate port numbers: the
 * "membership" descriptor as well as a direct communication channel.
 *
 * TODO fix this.
 */
public final class InternalDistributedMember
 implements DistributedMember,
    Externalizable, DataSerializableFixedID, ProfileId,
    VersionSource<DistributedMember>
{
  private final static long serialVersionUID = -2785249969777296507L;
  
  protected NetMember netMbr; // the underlying member object, e.g. from JGroups

  /**
   * This is the direct channel port. The underlying NetMember must be able to
   * serialize and deliver this value.
   */
  private int dcPort = -1;

  /**
   * This is the process id of this member on its machine. The underlying
   * NetMember must be able to serialize and deliver this value.
   */
  private int vmPid = -1;

  /**
   * This is a representation of the type of VM. The underlying NetMember must
   * be able to serialize and deliver this value.
   */
  private int vmKind = -1;
  
  /**
   * This is the view identifier where this ID was born, or zero if this is
   * a loner member
   */
  private int vmViewId = -1;
  
  /**
   * whether this is a partial member ID (without roles, durable attributes).
   * We use partial IDs in EventID objects to reduce their size.  It would be
   * better to use canonical IDs but there is currently no central mechanism
   * that would allow that for both server and client identifiers
   */
  private boolean isPartial;

  /** Internal list of group/role names for this member. */
  private String[] groups;

  /**
   * The roles, if any, of this member. Lazily created first time getRoles()
   * is called.
   */
  private volatile Set<Role> rolesSet = null;

  /** lock object used when getting/setting roles/rolesSet fields */
  private final Object rolesLock = new Object();

  /**
   * The name of this member's distributed system connection.
   * @see com.gemstone.gemfire.distributed.DistributedSystem#getName
   */
  private String name = null;

  /**
   * Unique tag (such as randomly generated bytes) to help enforce uniqueness.
   * Note: this should be displayable.
   */
  private String uniqueTag = null;

  /** serialization bit mask */
  private static final int SB_ENABLED_MASK = 0x1;

  /** serialization bit mask */
  private static final int COORD_ENABLED_MASK = 0x2;

  /** partial ID bit mask */
  private static final int PARTIAL_ID_MASK = 0x4;

  /** product version bit mask */
  private static final int VERSION_MASK = 0x8;

  /**
   * Representing the host name of this member.
   */
  private String hostName = null;

  private transient short version = Version.CURRENT_ORDINAL;
  private transient Version versionObj = Version.CURRENT;

  /**
   * User-defined attributes (id and timeout) used by durable clients.
   */
  private DurableClientAttributes durableClientAttributes = null;

  /** The versions in which this message was modified */
  private static final Version[] dsfidVersions = new Version[] {
        Version.GFE_71, Version.GFE_90 };

  private void defaultToCurrentHost() {
    int defaultDcPort = MemberAttributes.DEFAULT.getPort();
    this.dcPort = defaultDcPort;;
    this.vmKind = MemberAttributes.DEFAULT.getVmKind();
    this.vmPid = MemberAttributes.DEFAULT.getVmPid();
    this.name = MemberAttributes.DEFAULT.getName();
    this.groups = MemberAttributes.DEFAULT.getGroups();
    this.vmViewId = MemberAttributes.DEFAULT.getVmViewId();
    this.durableClientAttributes = MemberAttributes.DEFAULT.getDurableClientAttributes();
    try {
      if (SocketCreator.resolve_dns) {
        this.hostName = SocketCreator.getHostName(SocketCreator.getLocalHost());
      }
      else {
        this.hostName = SocketCreator.getLocalHost().getHostAddress();
      }
    }
    catch(UnknownHostException ee){
      throw new InternalGemFireError(ee);
    }
    synchPayload();
  }


  // Used only by Externalization
  public InternalDistributedMember() {
    this.groups = new String[0];
  }

  /**
   * Construct a InternalDistributedMember.  All fields are specified.<p>
   *
   * This, and the following constructor are the only valid ways to create an ID
   * for a distributed member for use
   * in the P2P cache.  Use of other constructors can break network-partition-detection.
   *
   * @param i
   * @param p
   *        the membership port
   * @param splitBrainEnabled whether this feature is enabled for the member
   * @param canBeCoordinator whether the member is eligible to be the membership coordinator
   * @param attr
   *        the member's attributes
   */
  public InternalDistributedMember(InetAddress i, int p, 
      boolean splitBrainEnabled, boolean canBeCoordinator, MemberAttributes attr) {
    this.dcPort = attr.getPort();
    this.vmPid = attr.getVmPid();
    this.vmKind = attr.getVmKind();
    this.vmViewId = attr.getVmViewId();
    this.name = attr.getName();
    this.groups = attr.getGroups();
    this.durableClientAttributes = attr.getDurableClientAttributes();
    this.netMbr = MemberFactory.newNetMember(i, p, splitBrainEnabled, canBeCoordinator, Version.CURRENT_ORDINAL, attr);
    this.hostName = SocketCreator.resolve_dns? SocketCreator.getHostName(i) : i.getHostAddress();
    this.version = netMbr.getVersionOrdinal();
    try {
      this.versionObj = Version.fromOrdinal(version, false);
    } catch (UnsupportedVersionException e) {
      this.versionObj = Version.CURRENT;
    }
//    checkHostName();
  }

  
  /**
   * Construct a InternalDistributedMember based on the given NetMember.<p>
   * This is not the preferred way of creating an instance since the NetMember
   * may not have all required information (e.g., a JGroups address without
   * direct-port and other information).
   * @param m
   */
  public InternalDistributedMember(NetMember m) {
    netMbr = m;

    MemberAttributes attr = m.getAttributes();
    this.hostName = SocketCreator.resolve_dns? SocketCreator.getHostName(m.getInetAddress()) :
      m.getInetAddress().getHostAddress();
//    checkHostName();
    if (attr == null) {
      // no extended information available, so this address is crippled
    }
    else {
      this.dcPort = attr.getPort();
      this.vmPid = attr.getVmPid();
      this.vmKind = attr.getVmKind();
      this.vmViewId = attr.getVmViewId();
      this.name = attr.getName();
      this.groups = attr.getGroups();
      this.durableClientAttributes = attr.getDurableClientAttributes();
    }
    this.version = m.getVersionOrdinal();
    try {
      this.versionObj = Version.fromOrdinal(version, false);
    } catch (UnsupportedVersionException e) {
      this.versionObj = Version.CURRENT;
    }
    cachedToString = null;
  }

  //  private void checkHostName() {
//    // bug #44858: debug method to find who is putting a host name instead of addr into an ID
//    if (!SocketCreator.resolve_dns
//        && this.hostName != null && this.hostName.length() > 0
//        && !Character.isDigit(this.hostName.charAt(0))) {
//      throw new RuntimeException("found hostname that doesn't start with a digit: " + this.hostName);
//    }
//  }

  /**
   * Create a InternalDistributedMember referring to the current host (as defined by the given
   * string).<p>
   *
   * <b>
   * [bruce]THIS METHOD IS FOR TESTING ONLY.  DO NOT USE IT TO CREATE IDs FOR
   * USE IN THE PRODUCT.  IT DOES NOT PROPERLY INITIALIZE ATTRIBUTES NEEDED
   * FOR P2P FUNCTIONALITY.
   * </b>
   *
   * 
   * @param i
   *          the hostname, must be for the current host
   * @param p
   *          the membership listening port
   * @throws UnknownHostException if the given hostname cannot be resolved
   */
  public InternalDistributedMember(String i, int p) throws UnknownHostException {
    netMbr = MemberFactory.newNetMember(i, p);
    defaultToCurrentHost();
    this.vmKind = DistributionManager.NORMAL_DM_TYPE;
  }

  /**
   * Create a InternalDistributedMember referring to the current host
   * (as defined by the given string) with additional info including optional
   * connection name and an optional unique string. Currently these two
   * optional fields (and this constructor) are only used by the
   * LonerDistributionManager.<p>
   *
   * < b>
   * [bruce]DO NOT USE THIS METHOD TO CREATE ANYTHING OTHER THAN A LONER ID
   * WITHOUT TALKING TO ME FIRST.  IT DOES NOT PROPERLY INITIALIZE THE ID.
   * </b>
   *
   * @param i
   *          the hostname, must be for the current host
   * @param p
   *          the membership listening port
   * @param n
   *          gemfire properties connection name
   * @param u
   *          unique string used make the member more unique
   * @throws UnknownHostException if the given hostname cannot be resolved
   */
  public InternalDistributedMember(String i, int p, String n, String u) throws UnknownHostException {
    netMbr = MemberFactory.newNetMember(i, p);
    defaultToCurrentHost();
    this.name = n;
    this.uniqueTag = u;
  }

  /**
   * Create a InternalDistributedMember  referring to the current host (as defined by the given
   * address).<p>
   *
   * <b>
   * [bruce]THIS METHOD IS FOR TESTING ONLY.  DO NOT USE IT TO CREATE IDs FOR
   * USE IN THE PRODUCT.  IT DOES NOT PROPERLY INITIALIZE ATTRIBUTES NEEDED
   * FOR P2P FUNCTIONALITY.
   * </b>
   *
   * 
   * @param i
   *          the hostname, must be for the current host
   * @param p
   *          the membership listening port
   */
  public InternalDistributedMember(InetAddress i, int p) {
    netMbr = MemberFactory.newNetMember(i, p);
    defaultToCurrentHost();
  }

  /**
   * Create a InternalDistributedMember as defined by the given address.
   * <p>
   * 
   * <b>
   * [bruce]THIS METHOD IS FOR TESTING ONLY.  DO NOT USE IT TO CREATE IDs FOR
   * USE IN THE PRODUCT.  IT DOES NOT PROPERLY INITIALIZE ATTRIBUTES NEEDED
   * FOR P2P FUNCTIONALITY.
   * </b>
   * 
   * @param addr 
   *        address of the server
   * @param p
   *        the listening port of the server
   * @param isCurrentHost
   *        true if the given host refers to the current host (bridge and
   *        gateway use false to create a temporary id for the OTHER side of a
   *        connection)
   */
  public InternalDistributedMember(InetAddress addr,
                                   int p,
                                   boolean isCurrentHost) {
    netMbr = MemberFactory.newNetMember(addr, p);
    if (isCurrentHost) {
      defaultToCurrentHost();
    }
  }

  /**
   * Return the underlying host address
   *
   * @return the underlying host address
   */
  public InetAddress getInetAddress()
  {
    return netMbr.getInetAddress();
  }

  public NetMember getNetMember() {
    return netMbr;
  }

  /**
   * Return the underlying port (membership port)
   * @return the underlying membership port
   */
  public int getPort()
  {
    return netMbr.getPort();
  }


  /**
   * Returns the port on which the direct channel runs
   */
  public int getDirectChannelPort()
  {
    assert !this.isPartial;
    return dcPort;
  }

  /**
   * [GemStone] Returns the kind of VM that hosts the distribution manager with
   * this address.
   *
   * @see com.gemstone.gemfire.distributed.internal.DistributionManager#getDistributionManagerType
   * @see com.gemstone.gemfire.distributed.internal.DistributionManager#NORMAL_DM_TYPE
   */
  public int getVmKind()
  {
    return vmKind;
  }
  
  /**
   * Returns the membership view ID that this member was born in. For
   * backward compatibility reasons this is limited to 16 bits.
   */
  public int getVmViewId() {
    return this.vmViewId;
  }

  /**
   * Returns an unmodifiable Set of this member's Roles.
   */
  public Set<Role> getRoles() {
    Set<Role> tmpRolesSet = this.rolesSet;
    if (tmpRolesSet != null) {
      return tmpRolesSet;
    }
    assert !this.isPartial;
    synchronized (this.rolesLock) {
      tmpRolesSet = this.rolesSet;
      if (tmpRolesSet == null) {
        final String[] tmpRoles = this.groups;
        // convert array of string role names to array of Roles...
        if (tmpRoles.length == 0) {
          tmpRolesSet = Collections.emptySet();
        }
        else {
          tmpRolesSet = new HashSet<Role>(tmpRoles.length);
          for (int i = 0; i < tmpRoles.length; i++) {
            tmpRolesSet.add(InternalRole.getRole(tmpRoles[i]));
          }
          tmpRolesSet = Collections.unmodifiableSet(tmpRolesSet);
        }
        this.rolesSet = tmpRolesSet;
      }
    }
    Assert.assertTrue(tmpRolesSet != null);
    return tmpRolesSet;
  }
  public List<String> getGroups() {
    return Collections.unmodifiableList(Arrays.asList(this.groups));
  }

  public void setGroups(String[] newGroups) {
    assert !this.isPartial;
    assert newGroups != null;
    synchronized (this.rolesLock) {
      this.groups = newGroups;
      synchPayload();
      this.rolesSet = null;
      this.cachedToString = null;
    }
  }

  private void synchPayload() {
    netMbr.setAttributes(new MemberAttributes(dcPort, vmPid, vmKind, 
        vmViewId, name, groups, durableClientAttributes));
  }

  public void setVmKind(int p)
  {
    vmKind = p;
    synchPayload();
    cachedToString = null;
  }
  
  public void setVmViewId(int p) {
    this.vmViewId = p;
    synchPayload();
  }

  /**
   * [GemStone] Returns the process id of the VM that hosts the distribution
   * manager with this address.
   *
   * @since 4.0
   */
  public int getVmPid()
  {
    return vmPid;
  }

  /**
   * [GemStone] Sets the process id of the VM that hosts the distribution
   * manager with this address.
   *
   * @since 4.0
   */
  public void setVmPid(int p)
  {
    this.vmPid = p;
    synchPayload();
    cachedToString = null;
  }

  /**
   * Returns the name of this member's distributed system connection or null
   * if no name was specified.
   * @see com.gemstone.gemfire.distributed.DistributedSystem#getName
   */
  public String getName() {
    String result = this.name;
    if (result == null) {
      result = "";
    }
    return result;
  }

  /**
   * Returns this member's unique tag (such as randomly generated bytes) or
   * null if no unique tag was created.
   */
  public String getUniqueTag() {
    return this.uniqueTag;
  }

  /**
   * Returns this client member's durable attributes or null if no durable
   * attributes were created.
   */
  public DurableClientAttributes getDurableClientAttributes() {
    assert !this.isPartial;
    return this.durableClientAttributes;
  }

  /**
   * implements the java.lang.Comparable interface
   *
   * @see java.lang.Comparable
   * @param o -
   *          the Object to be compared
   * @return a negative integer, zero, or a positive integer as this object is
   *         less than, equal to, or greater than the specified object.
   * @exception java.lang.ClassCastException -
   *              if the specified object's type prevents it from being compared
   *              to this Object.
   */
  public int compareTo(DistributedMember o)
  {
    if (this == o) {
      return 0;
    }
    // obligatory type check
    if ((o == null) || !(o instanceof InternalDistributedMember))
      throw new ClassCastException(LocalizedStrings.InternalDistributedMember_INTERNALDISTRIBUTEDMEMBERCOMPARETO_COMPARISON_BETWEEN_DIFFERENT_CLASSES.toLocalizedString());
    InternalDistributedMember other = (InternalDistributedMember)o;

    int myPort = getPort();
    int otherPort = other.getPort();
    if (myPort < otherPort)
      return -1;
    if (myPort > otherPort)
      return 1;


    InetAddress myAddr = getInetAddress();
    InetAddress otherAddr = other.getInetAddress();

    // Discard null cases
    if (myAddr == null && otherAddr == null) {
      if (myPort < otherPort)
        return -1;
      else if (myPort > otherPort)
        return 1;
      else
        return 0;
    }
    else if (myAddr == null) {
      return -1;
    }
    else if (otherAddr == null)
      return 1;

    byte[] myBytes = myAddr.getAddress();
    byte[] otherBytes = otherAddr.getAddress();

    if (myBytes != otherBytes) {
      for (int i = 0; i < myBytes.length; i++) {
        if (i >= otherBytes.length)
          return -1; // same as far as they go, but shorter...
        if (myBytes[i] < otherBytes[i])
          return -1;
        if (myBytes[i] > otherBytes[i])
          return 1;
      }
      if (myBytes.length > otherBytes.length)
        return 1; // same as far as they go, but longer...
    }

    if (this.name == null && other.name == null) {
      // do nothing
    } else if (this.name == null) {
      return -1;
    }
    else if (other.name == null) {
      return 1;
    }
    else {
      int i = this.name.compareTo(other.name);
      if (i != 0) {
        return i;
      }
    }

    if (this.uniqueTag == null && other.uniqueTag == null) {
      // not loners, so look at P2P view ID
      if (this.vmViewId < other.vmViewId) {
        return -1;
      } else if (this.vmViewId > other.vmViewId) {
        return 1;
      } // else they're the same, so continue
    } else if (this.uniqueTag == null) {
      return -1;
    }
    else if (other.uniqueTag == null) {
      return 1;
    }
    else {
      int i = this.uniqueTag.compareTo(other.uniqueTag);
      if (i != 0) {
        return i;
      }
    }

    // purposely avoid comparing roles
    // @todo Add durableClientAttributes to compare
    return 0;
  }

  @Override
  public boolean equals(Object obj)
  {
    if (this == obj) {
      return true;
    }
    // GemStone fix for 29125
    if ((obj == null) || !(obj instanceof InternalDistributedMember)) {
      return false;
    }
    return compareTo((InternalDistributedMember)obj) == 0;
  }

  @Override
  public int hashCode()
  {
    int result = 0;
     result = result + netMbr.getInetAddress().hashCode();
    result = result + getPort();
    return result;
  }

  private String shortName(String hostname)
  {
    if (hostname == null)
      return "<null inet_addr hostname>";
    int index = hostname.indexOf('.');

    if (index > 0 && !Character.isDigit(hostname.charAt(0)))
      return hostname.substring(0, index);
    else
      return hostname;
  }


  /** the cached string description of this object */
  private transient String cachedToString;

  @Override
  public String toString()
  {
    String result = cachedToString;
    if (result == null) {
      String host;

      InetAddress add = getInetAddress();
        if (add.isMulticastAddress())
          host = add.getHostAddress();
        else {
         // host = shortName(add.getHostName());
          host = SocketCreator.resolve_dns? shortName(this.hostName) : this.hostName;
        }
      final StringBuilder sb = new StringBuilder();

      sb.append(host);

      String myName = getName();
      if (vmPid > 0 || vmKind != DistributionManager.NORMAL_DM_TYPE || !"".equals(myName)) {
        sb.append("(");

        if (!"".equals(myName)) {
          sb.append(myName);
          if (vmPid > 0) {
            sb.append(':');
          }
        }

        if (vmPid > 0)
          sb.append(Integer.toString(vmPid));

        String vmStr = "";
        switch (vmKind) {
        case DistributionManager.NORMAL_DM_TYPE:
  //        vmStr = ":local"; // let this be silent
          break;
        case DistributionManager.LOCATOR_DM_TYPE:
          vmStr = ":locator";
          break;
        case DistributionManager.ADMIN_ONLY_DM_TYPE:
          vmStr = ":admin";
          break;
        case DistributionManager.LONER_DM_TYPE:
          vmStr = ":loner";
          break;
        default:
          vmStr = ":<unknown:" + vmKind + ">";
          break;
        }
        sb.append(vmStr);
        // for split-brain and security debugging we need to know if the
        // member has the "can't be coordinator" bit set
//        GMSMember jgm = (GMSMember)ipAddr;
//        if (!jgm.getAddress().canBeCoordinator()) {
//          sb.append("<p>");
//        }
        sb.append(")");
      }
      if (netMbr.splitBrainEnabled()) {
        if (netMbr.preferredForCoordinator()) {
          sb.append("<ec>");
        }
      }
      if (this.vmViewId >= 0) {
        sb.append("<v" + this.vmViewId + ">");
      }
      sb.append(":");
      sb.append(getPort());

//      if (dcPort > 0 && vmKind != DistributionManager.LONER_DM_TYPE) {
//        sb.append("/");
//        sb.append(Integer.toString(dcPort));
//      }

      if (vmKind == DistributionManager.LONER_DM_TYPE) {
        // add some more info that was added in 4.2.1 for loner bridge clients
        // impact on non-bridge loners is ok
        if (this.uniqueTag != null && this.uniqueTag.length() != 0) {
          sb.append(":").append(this.uniqueTag);
        }
        if (this.name != null && this.name.length() != 0) {
          sb.append(":").append(this.name);
        }
      }

      // add version if not current
      if (this.version != Version.CURRENT.ordinal()) {
        sb.append("(version:").append(Version.toString(this.version))
            .append(')');
      }

      // leave out Roles on purpose
      
//      if (netMbr instanceof GMSMember) {
//        sb.append("(UUID=").append(((GMSMember)netMbr).getUUID()).append(")");
//      }

      result = sb.toString();
      cachedToString = result;
    }
    return result;
  }

  private void readVersion(int flags, DataInput in) throws IOException {
    if ((flags & VERSION_MASK) != 0) {
      this.version = Version.readOrdinal(in);
      this.versionObj = Version.fromOrdinalNoThrow(this.version, false);
    }
  }

  /**
   * For Externalizable
   *
   * @see Externalizable
   */
  public void writeExternal(ObjectOutput out) throws IOException {
    Assert.assertTrue(vmKind > 0);

    // do it the way we like
    byte[] address = getInetAddress().getAddress();

    out.writeInt(address.length); // IPv6 compatible
    out.write(address);
    out.writeInt(getPort());

    DataSerializer.writeString(this.hostName, out);
    
    int flags = 0;
    if (netMbr.splitBrainEnabled()) flags |= SB_ENABLED_MASK;
    if (netMbr.preferredForCoordinator()) flags |= COORD_ENABLED_MASK;
    if (this.isPartial) flags |= PARTIAL_ID_MASK;
    // always write product version but enable reading from older versions
    // that do not have it
    flags |= VERSION_MASK;
    out.writeByte((byte)(flags & 0xff));

    out.writeInt(dcPort);
    out.writeInt(vmPid);
    out.writeInt(vmKind);
    out.writeInt(vmViewId);
    DataSerializer.writeStringArray(this.groups, out);

    DataSerializer.writeString(this.name, out);
    DataSerializer.writeString(this.uniqueTag, out);
    DataSerializer.writeString(this.durableClientAttributes==null ? "" : this.durableClientAttributes.getId(), out);
    DataSerializer.writeInteger(Integer.valueOf(this.durableClientAttributes==null ? 300 : this.durableClientAttributes.getTimeout()), out);
    Version.writeOrdinal(out, this.version, true);
    netMbr.writeAdditionalData(out);
  }

  /**
    * For Externalizable
    *
    * @see Externalizable
    */
   public void readExternal(ObjectInput in)
   throws IOException, ClassNotFoundException {
     int len = in.readInt(); // IPv6 compatible
     byte addr[] = new byte[len];
     in.readFully(addr);
     InetAddress inetAddr = InetAddress.getByAddress(addr);
     int port = in.readInt();
     
     this.hostName = DataSerializer.readString(in);

     int flags = in.readUnsignedByte();
     boolean sbEnabled = (flags & SB_ENABLED_MASK) != 0;
     boolean elCoord = (flags & COORD_ENABLED_MASK) != 0;
     this.isPartial = (flags & PARTIAL_ID_MASK) != 0;
     
     this.dcPort = in.readInt();
     this.vmPid = in.readInt();
     this.vmKind = in.readInt();
     this.vmViewId = in.readInt();
     this.groups = DataSerializer.readStringArray(in);

     this.name = DataSerializer.readString(in);
     this.uniqueTag = DataSerializer.readString(in);
     String durableId = DataSerializer.readString(in);
     int durableTimeout = DataSerializer.readInteger(in).intValue();
     this.durableClientAttributes = new DurableClientAttributes(durableId, durableTimeout);

     readVersion(flags, in);

     netMbr = MemberFactory.newNetMember(inetAddr, port, sbEnabled, elCoord, version,
         new MemberAttributes(dcPort, vmPid, vmKind, vmViewId, name, groups, durableClientAttributes));
     netMbr.readAdditionalData(in);

     Assert.assertTrue(this.vmKind > 0);
   }

  public int getDSFID() {
    return DISTRIBUTED_MEMBER;
  }

  public void toData(DataOutput out) throws IOException {
    toDataPre_GFE_9_0_0_0(out);
    getNetMember().writeAdditionalData(out);
  }
  
  
  public void toDataPre_GFE_9_0_0_0(DataOutput out) throws IOException {
    Assert.assertTrue(vmKind > 0);
    // NOTE: If you change the serialized format of this class
    //       then bump Connection.HANDSHAKE_VERSION since an
    //       instance of this class is sent during Connection handshake.
    DataSerializer.writeInetAddress(getInetAddress(), out);
    out.writeInt(getPort());

    DataSerializer.writeString(this.hostName, out);

    int flags = 0;
    if (netMbr.splitBrainEnabled()) flags |= SB_ENABLED_MASK;
    if (netMbr.preferredForCoordinator()) flags |= COORD_ENABLED_MASK;
    if (this.isPartial) flags |= PARTIAL_ID_MASK;
    // always write product version but enable reading from older versions
    // that do not have it
    flags |= VERSION_MASK;
    out.writeByte((byte)(flags & 0xff));
    
    out.writeInt(dcPort);
    out.writeInt(vmPid);
    out.writeByte(vmKind);
    DataSerializer.writeStringArray(this.groups, out);

    DataSerializer.writeString(this.name, out);
    if (this.vmKind == DistributionManager.LONER_DM_TYPE) {
      DataSerializer.writeString(this.uniqueTag, out);
    } else {  // added in 6.5 for unique identifiers in P2P
      DataSerializer.writeString(String.valueOf(this.vmViewId), out);
    }
    DataSerializer.writeString(this.durableClientAttributes==null ? "" : this.durableClientAttributes.getId(), out);
    DataSerializer.writeInteger(Integer.valueOf(this.durableClientAttributes==null ? 300 : this.durableClientAttributes.getTimeout()), out);
    Version.writeOrdinal(out, this.version, true);
  }

  public void toDataPre_GFE_7_1_0_0(DataOutput out) throws IOException {
      Assert.assertTrue(vmKind > 0);
    // [bruce] disabled to allow post-connect setting of the port for loner systems
//    Assert.assertTrue(getPort() > 0);
//    if (this.getPort() == 0) {
//      InternalDistributedSystem.getLoggerI18n().warning(LocalizedStrings.DEBUG,
//          "Serializing ID with zero port", new Exception("Stack trace"));
//    }

    // NOTE: If you change the serialized format of this class
    //       then bump Connection.HANDSHAKE_VERSION since an
    //       instance of this class is sent during Connection handshake.
    DataSerializer.writeInetAddress(getInetAddress(), out);
    out.writeInt(getPort());

    DataSerializer.writeString(this.hostName, out);

    int flags = 0;
    if (netMbr.splitBrainEnabled()) flags |= SB_ENABLED_MASK;
    if (netMbr.preferredForCoordinator()) flags |= COORD_ENABLED_MASK;
    if (this.isPartial) flags |= PARTIAL_ID_MASK;
    out.writeByte((byte)(flags & 0xff));
    
    out.writeInt(dcPort);
    out.writeInt(vmPid);
    out.writeByte(vmKind);
    DataSerializer.writeStringArray(this.groups, out);

    DataSerializer.writeString(this.name, out);
    if (this.vmKind == DistributionManager.LONER_DM_TYPE) {
      DataSerializer.writeString(this.uniqueTag, out);
    } else {  // added in 6.5 for unique identifiers in P2P
      DataSerializer.writeString(String.valueOf(this.vmViewId), out);
    }
    DataSerializer.writeString(this.durableClientAttributes==null ? "" : this.durableClientAttributes.getId(), out);
    DataSerializer.writeInteger(Integer.valueOf(this.durableClientAttributes==null ? 300 : this.durableClientAttributes.getTimeout()), out);
 
  }
  
  public void fromData(DataInput in)
  throws IOException, ClassNotFoundException {
    fromDataPre_9_0_0_0(in);
    netMbr.readAdditionalData(in);
  }
  
  public void fromDataPre_9_0_0_0(DataInput in)
  throws IOException, ClassNotFoundException {
    InetAddress inetAddr = DataSerializer.readInetAddress(in);
    int port = in.readInt();

    this.hostName = DataSerializer.readString(in);
    this.hostName = SocketCreator.resolve_dns? SocketCreator.getCanonicalHostName(inetAddr, hostName) : inetAddr.getHostAddress();

    int flags = in.readUnsignedByte();
    boolean sbEnabled = (flags & SB_ENABLED_MASK) != 0;
    boolean elCoord = (flags & COORD_ENABLED_MASK) != 0;
    this.isPartial = (flags & PARTIAL_ID_MASK) != 0;

    this.dcPort = in.readInt();
    this.vmPid = in.readInt();
    this.vmKind = in.readUnsignedByte();
    this.groups = DataSerializer.readStringArray(in);

    this.name = DataSerializer.readString(in);
    if (this.vmKind == DistributionManager.LONER_DM_TYPE) {
      this.uniqueTag = DataSerializer.readString(in);
    } else {
      String str = DataSerializer.readString(in);
      if (str != null) { // backward compatibility from earlier than 6.5
        this.vmViewId = Integer.parseInt(str);
      }
    }

    String durableId = DataSerializer.readString(in);
    int durableTimeout = DataSerializer.readInteger(in).intValue();
    this.durableClientAttributes = new DurableClientAttributes(durableId, durableTimeout);

    readVersion(flags, in);

    MemberAttributes attr = new MemberAttributes(this.dcPort, this.vmPid,
        this.vmKind, this.vmViewId, this.name, this.groups, this.durableClientAttributes);
    netMbr = MemberFactory.newNetMember(inetAddr, port, sbEnabled, elCoord, version, attr);

    synchPayload();

    Assert.assertTrue(this.vmKind > 0);
//    Assert.assertTrue(getPort() > 0);
  }

  public void fromDataPre_GFE_7_1_0_0(DataInput in)  throws IOException, ClassNotFoundException {
    InetAddress inetAddr = DataSerializer.readInetAddress(in);
    int port = in.readInt();

    this.hostName = DataSerializer.readString(in);
    this.hostName = SocketCreator.resolve_dns? SocketCreator.getCanonicalHostName(inetAddr, hostName) : inetAddr.getHostAddress();

    int flags = in.readUnsignedByte();
    boolean sbEnabled = (flags & SB_ENABLED_MASK) != 0;
    boolean elCoord = (flags & COORD_ENABLED_MASK) != 0;
    this.isPartial = (flags & PARTIAL_ID_MASK) != 0;

    this.dcPort = in.readInt();
    this.vmPid = in.readInt();
    this.vmKind = in.readUnsignedByte();
    this.groups = DataSerializer.readStringArray(in);

    this.name = DataSerializer.readString(in);
    if (this.vmKind == DistributionManager.LONER_DM_TYPE) {
      this.uniqueTag = DataSerializer.readString(in);
    } else {
      String str = DataSerializer.readString(in);
      if (str != null) { // backward compatibility from earlier than 6.5
        this.vmViewId = Integer.parseInt(str);
      }
    }

    String durableId = DataSerializer.readString(in);
    int durableTimeout = DataSerializer.readInteger(in).intValue();
    this.durableClientAttributes = new DurableClientAttributes(durableId, durableTimeout);

    MemberAttributes attr = new MemberAttributes(this.dcPort, this.vmPid,
        this.vmKind, this.vmViewId, this.name, this.groups, this.durableClientAttributes);
    netMbr = MemberFactory.newNetMember(inetAddr, port, sbEnabled, elCoord, 
        InternalDataSerializer.getVersionForDataStream(in).ordinal(), attr);

    synchPayload();

    Assert.assertTrue(this.vmKind > 0);
  }

  /** this writes just the parts of the ID that are needed for comparisons and communications */
   public static InternalDistributedMember readEssentialData(DataInput in)
     throws IOException, ClassNotFoundException {
     final InternalDistributedMember mbr = new InternalDistributedMember();
     mbr._readEssentialData(in);
     return mbr;
   }
   
   private void _readEssentialData(DataInput in)
     throws IOException, ClassNotFoundException {
     this.isPartial = true;
     InetAddress inetAddr = DataSerializer.readInetAddress(in);
     int port = in.readInt();

     this.hostName = SocketCreator.resolve_dns? SocketCreator.getHostName(inetAddr) : inetAddr.getHostAddress();

     int flags = in.readUnsignedByte();
     boolean sbEnabled = (flags & SB_ENABLED_MASK) != 0;
     boolean elCoord = (flags & COORD_ENABLED_MASK) != 0;

     this.vmKind = in.readUnsignedByte();
     

     if (this.vmKind == DistributionManager.LONER_DM_TYPE) {
       this.uniqueTag = DataSerializer.readString(in);
     } else {
       String str = DataSerializer.readString(in);
       if (str != null) { // backward compatibility from earlier than 6.5
         this.vmViewId = Integer.parseInt(str);
       }
     }

     this.name = DataSerializer.readString(in);

     MemberAttributes attr = new MemberAttributes(this.dcPort, this.vmPid,
         this.vmKind, this.vmViewId, this.name, this.groups, this.durableClientAttributes);
     netMbr = MemberFactory.newNetMember(inetAddr, port, sbEnabled, elCoord, 
         InternalDataSerializer.getVersionForDataStream(in).ordinal(), attr);

     synchPayload();
   }


   public void writeEssentialData(DataOutput out) throws IOException {
     Assert.assertTrue(vmKind > 0);
     DataSerializer.writeInetAddress(getInetAddress(), out);
     out.writeInt(getPort());

     int flags = 0;
     if (netMbr.splitBrainEnabled()) flags |= SB_ENABLED_MASK;
     if (netMbr.preferredForCoordinator()) flags |= COORD_ENABLED_MASK;
     flags |= PARTIAL_ID_MASK;
     out.writeByte((byte)(flags & 0xff));
     
//     out.writeInt(dcPort);
     out.writeByte(vmKind);

     if (this.vmKind == DistributionManager.LONER_DM_TYPE) {
       DataSerializer.writeString(this.uniqueTag, out);
     } else {  // added in 6.5 for unique identifiers in P2P
       DataSerializer.writeString(String.valueOf(this.vmViewId), out);
     }
     // write name last to fix bug 45160
     DataSerializer.writeString(this.name, out);
   }

  /**
   * [GemStone] Set the direct channel port
   */
  public void setDirectChannelPort(int p)
  {
    dcPort = p;
    synchPayload();
  }
  
  /**
   * Set the membership port.  This is done in loner systems using
   * client/server connection information to help form a unique ID
   */
  public void setPort(int p) {
    assert this.vmKind == DistributionManager.LONER_DM_TYPE;
    this.netMbr.setPort(p);
    synchPayload();
    cachedToString = null;
  }
  
  /** drop the cached toString rep of this ID */
  public void dropCachedString() {
    this.cachedToString = null;
  }

  public String getHost() {
    return this.netMbr.getInetAddress().getCanonicalHostName();
  }

  public int getProcessId() {
    return this.vmPid;
  }

  public String getId() {
    return toString();
  }
    /*if (this.ipAddr == null) {
      return "<null>";
    }
    else {
      StringBuffer sb = new StringBuffer();
      InetAddress addr = this.ipAddr.getIpAddress();
      if(addr.isMulticastAddress()) {
        sb.append(addr.getHostAddress());
      } else {
        appendShortName(addr.getHostName(), sb);
      }
      if (this.vmPid != 0) {
        sb.append("(");
        sb.append(this.vmPid);
        sb.append(")");
      }
      sb.append(":");
      sb.append(this.ipAddr.getPort());
      return sb.toString();
    }
  }

  // Helper method for getId()... copied from IpAddress.
  private void appendShortName(String hostname, StringBuffer sb) {
    if (hostname == null) return;
    int index = hostname.indexOf('.');
    if(index > 0 && !Character.isDigit(hostname.charAt(0))) {
      sb.append(hostname.substring(0, index));
    } else {
      sb.append(hostname);
    }
  }*/

  public final Version getVersionObject() {
    return this.versionObj;
  }
  
  @Override
  public Version[] getSerializationVersions() {
    return dsfidVersions;
  }


  @Override
  public int getSizeInBytes() {
  
    int size = 0;
  
    // ipaddr:  1 byte length + 4 bytes (IPv4) or 16 bytes (IPv6)
    if (netMbr.getInetAddress() instanceof Inet4Address){
      size += 5;
    } else {
      size += 17;
    }
    
    // port:  4 bytes
    // flags: 1 byte
    //vmKind: 1 byte
    size += 6;
    
    // viewID:  String(1+1+numchars)
    size += (2+ String.valueOf(this.vmViewId).length());
    
    // empty name: String(1+1)
    size += 2;
    
    return size;
  }
}
