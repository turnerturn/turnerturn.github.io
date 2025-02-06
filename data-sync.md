---


---

<pre><code>package  com.example.demo;

  

import  java.time.LocalDateTime;

import  java.util.HashSet;

import  java.util.List;

import  java.util.Map;

import  java.util.Optional;

import  java.util.Set;

import  java.util.stream.Collectors;

  

import  org.springframework.beans.factory.annotation.Autowired;

import  org.springframework.beans.factory.annotation.Value;

import  org.springframework.boot.context.properties.ConfigurationProperties;

import  org.springframework.core.env.Environment;

import  org.springframework.data.jpa.repository.JpaRepository;

import  org.springframework.data.jpa.repository.Modifying;

import  org.springframework.data.jpa.repository.Query;

import  org.springframework.data.repository.query.Param;

import  org.springframework.stereotype.Component;

import  org.springframework.stereotype.Repository;

import  org.springframework.stereotype.Service;

  

import  jakarta.transaction.Transactional;

import  lombok.AllArgsConstructor;

import  lombok.Getter;

import  lombok.RequiredArgsConstructor;

import  lombok.Setter;

import  lombok.extern.slf4j.Slf4j;

  

@Slf4j

@Component

@AllArgsConstructor

@ConfigurationProperties(prefix  =  "cpam")

public  class  CpamPersonsProcessor {

  

/*

* TODO: logging, gradle init, spring dependencies, @Query, and hibernate entities.

* TODO configure these via .yml

*

* features: cpam-person-processor: true, delete-carrier-assignments-with-reassigned-drivers: false, reassign-carrier-assignments-from-reassigned-drivers: true, delete-carrier-assignments-with-insufficient-accesslevel: true, delete-carrier-assignments-with-unassigned-badges: true, swift-dispatching: false

*/

  

@Autowired

private  Environment  env;

  

@Autowired

private  DriverService  driverService;

  

@Getter

@Setter

private  Map&lt;String,String&gt; accessLevels;

@Getter

@Setter

@Value("${SERVICE_HOST}")

private  String  hostname;

  

private  CpamPersonsProcessor(){}

  
  

protected  boolean  containsMyTerminalAccessLevel(Badge  badge) {

log.trace("Checking terminal access level for badge: {}", badge);

return  badge  !=  null  &amp;&amp;  badge.getAccessLevels() !=  null  &amp;&amp;  badge.getAccessLevels().stream().map(AccessLevel::getName).collect(Collectors.toList()).contains(accessLevels.get(hostname));

}

  

protected  boolean  matches(Integer  input1, Integer  input2) {

log.trace("Matching input1: {} with input2: {}", input1, input2);

return (input1  !=  null  &amp;&amp;  input1.equals(input2)) || (input1  ==  null  &amp;&amp;  input2  ==  null);

}

  

protected  Set&lt;Integer&gt; collectBadgeNumbers(CpamPerson  person){

log.trace("Collecting badge numbers for person: {}", person);

return  person.getBadges().stream().map(Badge::getCardNumber).collect(Collectors.toSet());

}

  

public  void  process(List&lt;CpamPerson&gt; updatedCpamPersons) {

log.trace("Processing updated CPAM persons");

  

if(!env.getProperty("features.cpam-person-processor", Boolean.class, false)){

log.info("CPAM person processor feature is disabled");

return;

}

  

if(hostname  ==  null  ||  hostname.isEmpty()) {

throw  new  RuntimeException("Hostname is not set");

}

if(accessLevels  ==  null  ||  !accessLevels.containsKey(hostname)) {

throw  new  RuntimeException("Access level is not configured for hostname");

}

  

for (CpamPerson  cpamPerson  :  updatedCpamPersons) {

  

if (!driverService.driverExistsByCpamPerson(cpamPerson)) {

log.info("Inserting new driver for CPAM person: {}", cpamPerson);

driverService.insertDriverByCpamPerson(cpamPerson);

} else {

log.info("Updating driver for CPAM person: {}", cpamPerson);

driverService.updateDriverByCpamPerson(cpamPerson);

}

  

Integer  driverId  =  driverService.findDriverIdByCpamPerson(cpamPerson)

.orElseThrow(() -&gt;  new  RuntimeException("Driver must exist by CPAM person unid. Escalate this issue to the development team."));

  

Set&lt;Integer&gt; personBadgeNumbers  =  collectBadgeNumbers(cpamPerson);

  

//handle events where carrier-driver's badge was once assigned to cpam person but now it is not.

// this also captures scenario if badge was assigned to carrier-driver's driver-id of a driver when the driver's cpam person could have never existed or driver's cpam person may have never actually had this badge assigned.

// disable this logic if we want the ability to assign carrier-drivers to badge and driver regardless of cpam's person and badge configurations.

if(env.getProperty("features.delete-carrier-assignments-with-unassigned-badges", Boolean.class, false)){

for(Integer  badgeNumber  :  driverService.findBadgeNumbersByDriverId(driverId)){

if(!personBadgeNumbers.contains(badgeNumber)){

log.info("Badge no longer assigned to driver's cpam person. Carrier-drivers will be deleted by badge number. (badge={})", badgeNumber);

driverService.deleteCarrierDriversByBadgeNumber(badgeNumber);

}

}

} else {

log.debug("Feature to delete carrier assignments with unassigned badges is disabled");

}

  

for (Badge  badge  :  cpamPerson.getBadges()) {

Set&lt;Integer&gt; effectedDriverIds  =  new  HashSet&lt;&gt;();

// collect effected driverIds so we can process later for log messages and swift dispatching feature.

// this filtered set includes all driverIds which do not match the current driverId. (i.e.. effected driver's carrier-drivers were reassigned to new driver.)

effectedDriverIds.addAll(driverService.findDriverIdsByBadge(badge.getCardNumber()).stream().filter(effectedDriverId  -&gt;  !matches(effectedDriverId, driverId)).collect(Collectors.toSet()));

  

// handle events where badge is assigned to a driver with insufficient access level.

if (!containsMyTerminalAccessLevel(badge)) {

log.info("Cpam person's badge does not have this terminal's configured access-level. (badge={})", badge.getCardNumber());

if(env.getProperty("features.delete-carrier-assignments-with-insufficient-accesslevel", Boolean.class, false)){

log.info("Deleting carrier driver for badge with insufficient access level. (badge={})", badge.getCardNumber());

driverService.deleteCarrierDriversByBadgeNumber(badge.getCardNumber());

} else {

log.debug("Feature to delete carrier assignments with insufficient access level is disabled.");

log.debug("Enable this feature by setting features.delete-carrier-assignments-with-insufficient-accesslevel=true in application.yml");

}

} else {

log.info("Updating carrier-driver records for badge. (badge={})", badge.getCardNumber());

// this applies badge details to all carrier-drivers assigned to badge number. Regardless of carrier-driver's driverId.

driverService.updateCarrierDriversByBadge(badge);

  

if(env.getProperty("features.reassign-carrier-assignments-from-reassigned-drivers", Boolean.class, false)){

//Updates all carrier-drivers assigned to badge number by setting driverId to curren cpam person's driverId.

log.info("Reassigning existing carrier-drivers to new driver-id. (badge={}, driver={})", badge.getCardNumber(), driverId);

driverService.updateCarrierDriverIdsByBadge(driverId, badge);

} else {

log.debug("Feature to reassign existing carrier-drivers to new driver-id is disabled.");

log.debug("Enable this feature by setting features.reassign-carrier-assignments-from-reassigned-drivers=true in application.yml");

}

if(env.getProperty("features.delete-carrier-assignments-with-reassigned-drivers", Boolean.class, false)){

effectedDriverIds.forEach(effectedDriverId  -&gt;  driverService.deleteCarrierDriversWithReassignedBadge(effectedDriverId, badge.getCardNumber()));

} else {

log.debug("Feature to delete carrier assignments with reassigned drivers is disabled");

}

// we want driver and badge to be assigned to -1 carrier if it isn't already.

// This is for legacy business logic which resides in the guts of our ecosystem.

if (!driverService.temporaryCarrierDriverExistsByBadge(badge)) {

log.debug("Inserting temporary carrier driver for badge and driver. (badge={}, driver={})", badge.getCardNumber(),driverId);

driverService.insertTemporaryCarrierDriverByBadge(driverId, badge);

}

}

  

// process log messages to indicate which drivers were effected by the processing of this cpam-person.

// implement tasks required for swift's dispatching feature when swift-dispatching feature is enabled.

for (Integer  effectedDriverId  :  effectedDriverIds) {

driverService.findDriverById(effectedDriverId).ifPresent(driver  -&gt; {

log.info("carrier-driver assignments were reassigned to new driver. (old-driver={}, new-driver={})", effectedDriverId, driverId);

if (env.getProperty("features.swift-dispatching", Boolean.class, false)) {

driverService.publishSwiftDispatchingEvent(driver);

} else {

log.debug("Feature for swift dispatching is disabled");

log.debug("Enable this feature by setting features.swift-dispatching=true in application.yml");

}

});

}

}

driverService.findDriverById(driverId).ifPresent(driver  -&gt; {

log.info("Driver updated/created for cpam-person. (driver-id={})", driverId);

log.debug("Driver details: {}", driver);

if(env.getProperty("features.swift-dispatching", Boolean.class, false)){

driverService.publishSwiftDispatchingEvent(driver);

} else {

log.debug("Feature for swift dispatching is disabled");

}

});

}

}

}

  
  

@Service

@Transactional

@RequiredArgsConstructor

class  DriverService {

  

private  final  DriverRepository  driverRepository;

private  final  CarrierDriverRepository  carrierDriverRepository;

  

public  void  publishSwiftDispatchingEvent(Driver  driver){

//TODO publish outbox event message.

}

public  boolean  driverExistsByCpamPerson(CpamPerson  cpamPerson){

return  driverRepository.driverExistsByCpamPersonUnid(cpamPerson.getUnid());

}

public  Optional&lt;Integer&gt; findDriverIdByCpamPerson(CpamPerson  cpamPerson){

return  driverRepository.findDriverIdByCpamPersonUnid(cpamPerson.getUnid());

}

public  Optional&lt;Driver&gt; findDriverById(Integer  driverId){

return  driverRepository.findDriverById(driverId);

}

  

public  void  updateDriverByCpamPerson(CpamPerson  cpamPerson){

driverRepository.updateDriverByCpamPersonUnid(cpamPerson.getUnid(), cpamPerson.getFirstName(), cpamPerson.getLastName(), cpamPerson.getMiddleInitial(), cpamPerson.getPersonId(), cpamPerson.getEffectiveDate(), cpamPerson.getExpirationDate(), cpamPerson.getStatus());

}

public  void  insertDriverByCpamPerson(CpamPerson  cpamPerson){

driverRepository.insertDriver(cpamPerson.getUnid(), cpamPerson.getFirstName(), cpamPerson.getLastName(), cpamPerson.getMiddleInitial(), cpamPerson.getPersonId(), cpamPerson.getEffectiveDate(), cpamPerson.getExpirationDate(), cpamPerson.getStatus());

}

public  Set&lt;Integer&gt; findDriverIdsByBadge(Integer  badgeNumber){

return  carrierDriverRepository.findDriverIdsByBadge(badgeNumber);

}

public  Set&lt;Integer&gt; findBadgeNumbersByDriverId(Integer  driverId){

return  carrierDriverRepository.findBadgeNumbersByDriverId(driverId);

}

public  boolean  temporaryCarrierDriverExistsByBadge(Badge  badge){

return  carrierDriverRepository.existsByCarrierNumberAndBadge("-1",badge.getCardNumber());

}

  

public  void  deleteCarrierDriversByBadgeNumber(Integer  badgeNumber){

carrierDriverRepository.deleteByBadge(badgeNumber);

}

/**

* Merges badge details into carrier-drivers assigned to badge.number.

* Sets carrier-driver's driverId to specified driverId.

* @param  driverId

* @param  badge

*/

public  void  updateCarrierDriversByBadge( Badge  badge){

carrierDriverRepository.updateCarrierDriversByBadge( badge.getCardNumber(), badge.getEffectiveDate(), badge.getExpirationDate(), badge.getStatus());

}

public  void  deleteCarrierDriversWithReassignedBadge(Integer  driverId,Integer  badge){

carrierDriverRepository.deleteCarrierDriversWithReassignedBadge(driverId, badge);

}

/**

* Sets carrier-driver's driverId to specified driverId.

* @param  badge

*/

public  void  updateCarrierDriverIdsByBadge(Integer  driverId, Badge  badge){

carrierDriverRepository.updateCarrierDriverIdByBadge( driverId, badge.getCardNumber());

}

public  void  insertTemporaryCarrierDriverByBadge(Integer  driverId, Badge  badge){

carrierDriverRepository.insertCarrierDriverByBadge( driverId,"-1", badge.getCardNumber(), badge.getEffectiveDate(), badge.getExpirationDate(), badge.getStatus());

}

}

  

@Repository

interface  DriverRepository  extends  JpaRepository&lt;Driver, Integer&gt; {

@Query("SELECT CASE WHEN COUNT(d) &gt; 0 THEN true ELSE false END FROM Driver d WHERE d.unid = :cpamPersonUnid")

public  boolean  driverExistsByCpamPersonUnid(@Param("cpamPersonUnid") String  cpamPersonUnid);

  

@Query("SELECT d.id FROM Driver d WHERE d.unid = :cpamPersonUnid")

public  Optional&lt;Integer&gt; findDriverIdByCpamPersonUnid(@Param("cpamPersonUnid") String  cpamPersonUnid);

  

@Query("SELECT d FROM Driver d WHERE d.id = :driverId")

public  Optional&lt;Driver&gt; findDriverById(@Param("driverId") Integer  driverId);

  

@Modifying

@Query("UPDATE Driver d SET d.unid = :cpamPersonUnid, d.firstName = :firstName, d.lastName = :lastName, d.middleInitial = :middleInitial, d.personId = :personId, d.effectiveDate = :effectiveDate, d.expirationDate = :expirationDate, d.status = :status WHERE d.unid = :cpamPersonUnid")

public  void  updateDriverByCpamPersonUnid(@Param("cpamPersonUnid") String  cpamPersonUnid, @Param("firstName") String  firstName, @Param("lastName") String  lastName, @Param("middleInitial") String  middleInitial, @Param("personId") String  personId, @Param("effectiveDate") LocalDateTime  effectiveDate, @Param("expirationDate") LocalDateTime  expirationDate, @Param("status") String  status);

  

@Modifying

@Query("INSERT INTO Driver (unid, firstName, lastName, middleInitial, personId, effectiveDate, expirationDate, status) VALUES (:cpamPersonUnid, :firstName, :lastName, :middleInitial, :personId, :effectiveDate, :expirationDate, :status)")

public  void  insertDriver(@Param("cpamPersonUnid") String  cpamPersonUnid, @Param("firstName") String  firstName, @Param("lastName") String  lastName, @Param("middleInitial") String  middleInitial, @Param("personId") String  personId, @Param("effectiveDate") LocalDateTime  effectiveDate, @Param("expirationDate") LocalDateTime  expirationDate, @Param("status") String  status);

}

@Repository

interface  CarrierDriverRepository  extends  JpaRepository&lt;CarrierDriver, CarrierDriverId&gt; {

@Query("SELECT driverId FROM CarrierDriver WHERE badgeId = :badge")

public  Set&lt;Integer&gt; findDriverIdsByBadge(@Param("badge") Integer  badge);

@Query("SELECT badgeId FROM CarrierDriver WHERE driverId = :driverId")

public  Set&lt;Integer&gt; findBadgeNumbersByDriverId(@Param("driverId") Integer  driverId);

  

@Query("SELECT CASE WHEN COUNT(cd) &gt; 0 THEN true ELSE false END FROM CarrierDriver cd WHERE cd.carrierId = :carrierId AND cd.badgeId = :badge")

public  boolean  existsByCarrierNumberAndBadge(@Param("carrierId") String  carrierId, @Param("badge") Integer  badge);

@Modifying

@Query("DELETE FROM CarrierDriver WHERE badgeId = :badge")

public  void  deleteByBadge(@Param("badge") Integer  badge);

@Modifying

@Query("DELETE FROM CarrierDriver WHERE driverId = :driverId AND badgeId = :badge")

public  void  deleteCarrierDriversWithReassignedBadge(@Param("driverId") Integer  driverId, @Param("badge") Integer  badge);

  

@Modifying

@Query("UPDATE CarrierDriver SET effectiveDate = :effectiveDate, expirationDate = :expirationDate, status = :status WHERE badgeId = :badgeId")

public  void  updateCarrierDriversByBadge(@Param("badgeId") Integer  badgeId, @Param("effectiveDate") LocalDateTime  effectiveDate, @Param("expirationDate") LocalDateTime  expirationDate, @Param("status") String  status);

@Modifying

@Query("UPDATE CarrierDriver SET driverId = :driverId WHERE badgeId = :badgeId")

public  void  updateCarrierDriverIdByBadge(@Param("driverId") Integer  driverId, @Param("badgeId") Integer  badgeId);

@Modifying

@Query("INSERT INTO CarrierDriver (driverId, carrierId, badgeId, effectiveDate, expirationDate, status) VALUES (:driverId, :carrierId, :badgeId, :effectiveDate, :expirationDate, :status)")

public  void  insertCarrierDriverByBadge(@Param("driverId") Integer  driverId, @Param("carrierId") String  carrierId, @Param("badgeId") Integer  badgeId, @Param("effectiveDate") LocalDateTime  effectiveDate, @Param("expirationDate") LocalDateTime  expirationDate, @Param("status") String  status);

}
</code></pre>
<pre><code>package  com.example.demo;

import  static  org.mockito.ArgumentMatchers.*;

import  static  org.mockito.Mockito.*;

  

import  java.time.LocalDateTime;

import  java.util.Arrays;

import  java.util.Collections;

import  java.util.HashMap;

import  java.util.HashSet;

import  java.util.Optional;

  

import  org.junit.jupiter.api.BeforeEach;

import  org.junit.jupiter.api.Test;

import  org.mockito.InjectMocks;

import  org.mockito.Mock;

import  org.mockito.MockitoAnnotations;

import  org.springframework.core.env.Environment;

  
  

public  class  CpamPersonsProcessorTest {

  

@Mock

private  Environment  env;

  

@Mock

private  DriverService  driverService;

  

@InjectMocks

private  CpamPersonsProcessor  cpamPersonsProcessor;

  

@BeforeEach

void  setUp() {

MockitoAnnotations.openMocks(this);

cpamPersonsProcessor.setHostname("test-host");

cpamPersonsProcessor.setAccessLevels(new  HashMap&lt;&gt;() {{

put("test-host", "test-access-level");

}});

}

  

@Test

void  testProcessWhenCpamPersonProcessorDisabled() {

when(env.getProperty("features.cpam-person-processor", Boolean.class, false)).thenReturn(false);

  

cpamPersonsProcessor.process(Collections.emptyList());

  

verify(driverService, never()).driverExistsByCpamPerson(any());

}

  

@Test

void  testProcessWhenMergeCarrierAssignmentsFromReassignedDriversEnabled() {

when(env.getProperty("features.cpam-person-processor", Boolean.class, false)).thenReturn(true);

when(env.getProperty("features.reassign-carrier-assignments-from-reassigned-drivers", Boolean.class, false)).thenReturn(true);

when(env.getProperty("features.delete-carrier-assignments-with-reassigned-drivers", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.delete-carrier-assignments-with-insufficient-accesslevel", Boolean.class, false)).thenReturn(true);

when(env.getProperty("features.delete-carrier-assignments-with-unassigned-badges", Boolean.class, false)).thenReturn(true);

when(env.getProperty("features.swift-dispatching", Boolean.class, false)).thenReturn(false);

  

CpamPerson  cpamPerson  =  new  CpamPerson();

cpamPerson.setBadges(Collections.singletonList(new  Badge(1, "active", LocalDateTime.now(), LocalDateTime.now().plusDays(1), Collections.singletonList(new  AccessLevel("","test-access-level")))));

cpamPerson.setUnid("test-unid");

cpamPerson.getBadges().get(0).setCardNumber(1);

when(driverService.driverExistsByCpamPerson(cpamPerson)).thenReturn(false);

when(driverService.findDriverIdByCpamPerson(cpamPerson)).thenReturn(Optional.of(1));

when(driverService.findBadgeNumbersByDriverId(1)).thenReturn(Collections.singleton(1));

when(driverService.findDriverIdsByBadge(any())).thenReturn(Collections.singleton(2));

  

cpamPersonsProcessor.process(Collections.singletonList(cpamPerson));

  

verify(driverService).insertDriverByCpamPerson(cpamPerson);

verify(driverService).updateCarrierDriverIdsByBadge(1, cpamPerson.getBadges().get(0));

}

  

@Test

void  testProcessWhenDeleteCarrierAssignmentsWithReassignedDriversEnabled() {

when(env.getProperty("features.cpam-person-processor", Boolean.class, false)).thenReturn(true);

when(env.getProperty("features.reassign-carrier-assignments-from-reassigned-drivers", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.delete-carrier-assignments-with-reassigned-drivers", Boolean.class, false)).thenReturn(true);

when(env.getProperty("features.delete-carrier-assignments-with-insufficient-accesslevel", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.delete-carrier-assignments-with-unassigned-badges", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.swift-dispatching", Boolean.class, false)).thenReturn(false);

  

CpamPerson  cpamPerson  =  new  CpamPerson();

cpamPerson.setBadges(Collections.singletonList(new  Badge(1, "active", LocalDateTime.now(), LocalDateTime.now().plusDays(1), Collections.singletonList(new  AccessLevel("","test-access-level")))));

cpamPerson.setUnid("test-unid");

cpamPerson.getBadges().get(0).setCardNumber(1);

when(driverService.driverExistsByCpamPerson(any( ))).thenReturn(false);

when(driverService.findDriverIdByCpamPerson(any( ))).thenReturn(Optional.of(1));

when(driverService.findBadgeNumbersByDriverId(1)).thenReturn(Collections.singleton(1));

when(driverService.findDriverIdsByBadge(cpamPerson.getBadges().get(0).getCardNumber())).thenReturn(new  HashSet&lt;&gt;(Arrays.asList(1, 2)));

cpamPersonsProcessor.process(Collections.singletonList(cpamPerson));

  

verify(driverService).insertDriverByCpamPerson(cpamPerson);

verify(driverService,atLeastOnce()).deleteCarrierDriversWithReassignedBadge(any(), any());

}

  

@Test

void  testProcessWhenDeleteCarrierAssignmentsWithInsufficientAccessLevelEnabled() {

when(env.getProperty("features.cpam-person-processor", Boolean.class, false)).thenReturn(true);

when(env.getProperty("features.reassign-carrier-assignments-from-reassigned-drivers", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.delete-carrier-assignments-with-reassigned-drivers", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.delete-carrier-assignments-with-insufficient-accesslevel", Boolean.class, false)).thenReturn(true);

when(env.getProperty("features.delete-carrier-assignments-with-unassigned-badges", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.swift-dispatching", Boolean.class, false)).thenReturn(false);

  

CpamPerson  cpamPerson  =  new  CpamPerson();

cpamPerson.setBadges(Collections.singletonList(new  Badge(1, "active", LocalDateTime.now(), LocalDateTime.now().plusDays(1), Collections.singletonList(new  AccessLevel("","wrong-access-level")))));

cpamPerson.getBadges().get(0).setCardNumber(1);

cpamPerson.setUnid("test-unid");

  

when(driverService.driverExistsByCpamPerson(cpamPerson)).thenReturn(false);

when(driverService.findDriverIdByCpamPerson(cpamPerson)).thenReturn(Optional.of(1));

when(driverService.findBadgeNumbersByDriverId(1)).thenReturn(Collections.singleton(1));

when(driverService.findDriverIdsByBadge(any())).thenReturn(Collections.singleton(2));

  

cpamPersonsProcessor.process(Collections.singletonList(cpamPerson));

  

verify(driverService).insertDriverByCpamPerson(cpamPerson);

verify(driverService).deleteCarrierDriversByBadgeNumber(1);

}

  

@Test

void  testProcessWhenDeleteCarrierAssignmentsWithUnassignedBadgesEnabled() {

when(env.getProperty("features.cpam-person-processor", Boolean.class, false)).thenReturn(true);

when(env.getProperty("features.reassign-carrier-assignments-from-reassigned-drivers", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.delete-carrier-assignments-with-reassigned-drivers", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.delete-carrier-assignments-with-insufficient-accesslevel", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.delete-carrier-assignments-with-unassigned-badges", Boolean.class, false)).thenReturn(true);

when(env.getProperty("features.swift-dispatching", Boolean.class, false)).thenReturn(false);

  

CpamPerson  cpamPerson  =  new  CpamPerson();

cpamPerson.setBadges(Collections.singletonList(new  Badge(1, "active", LocalDateTime.now(), LocalDateTime.now().plusDays(1), Collections.singletonList(new  AccessLevel("","test-access-level")))));

cpamPerson.setUnid("test-unid");

  

when(driverService.driverExistsByCpamPerson(cpamPerson)).thenReturn(false);

when(driverService.findDriverIdByCpamPerson(cpamPerson)).thenReturn(Optional.of(1));

when(driverService.findBadgeNumbersByDriverId(1)).thenReturn(Collections.singleton(2));

when(driverService.findDriverIdsByBadge(any())).thenReturn(Collections.singleton(2));

  

cpamPersonsProcessor.process(Collections.singletonList(cpamPerson));

  

verify(driverService).insertDriverByCpamPerson(cpamPerson);

verify(driverService).deleteCarrierDriversByBadgeNumber(2);

}

  

@Test

void  testProcessWhenSwiftDispatchingEnabled() {

when(env.getProperty("features.cpam-person-processor", Boolean.class, false)).thenReturn(true);

when(env.getProperty("features.reassign-carrier-assignments-from-reassigned-drivers", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.delete-carrier-assignments-with-reassigned-drivers", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.delete-carrier-assignments-with-insufficient-accesslevel", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.delete-carrier-assignments-with-unassigned-badges", Boolean.class, false)).thenReturn(false);

when(env.getProperty("features.swift-dispatching", Boolean.class, false)).thenReturn(true);

  

CpamPerson  cpamPerson  =  new  CpamPerson();

cpamPerson.setBadges(Collections.singletonList(new  Badge(1, "active", LocalDateTime.now(), LocalDateTime.now().plusDays(1), Collections.singletonList(new  AccessLevel("","test-access-level")))));

cpamPerson.setUnid("test-unid");

  

when(driverService.driverExistsByCpamPerson(cpamPerson)).thenReturn(false);

when(driverService.findDriverIdByCpamPerson(cpamPerson)).thenReturn(Optional.of(1));

when(driverService.findBadgeNumbersByDriverId(1)).thenReturn(Collections.singleton(1));

when(driverService.findDriverIdsByBadge(any())).thenReturn(Collections.singleton(2));

when(driverService.findDriverById(1)).thenReturn(Optional.of(new  Driver()));

  

cpamPersonsProcessor.process(Collections.singletonList(cpamPerson));

  

verify(driverService).insertDriverByCpamPerson(cpamPerson);

verify(driverService).publishSwiftDispatchingEvent(any());

}

}
</code></pre>
<pre><code>package  com.example.demo;

  

import  java.time.LocalDateTime;

import  java.util.Set;

  

import  jakarta.persistence.Column;

import  jakarta.persistence.Entity;

import  jakarta.persistence.FetchType;

import  jakarta.persistence.GeneratedValue;

import  jakarta.persistence.GenerationType;

import  jakarta.persistence.Id;

import  jakarta.persistence.JoinColumn;

import  jakarta.persistence.OneToMany;

import  jakarta.persistence.Table;

import  lombok.AllArgsConstructor;

import  lombok.Builder;

import  lombok.Getter;

import  lombok.NoArgsConstructor;

import  lombok.Setter;

import  lombok.ToString;

  

@Getter

@Setter

@NoArgsConstructor

@AllArgsConstructor

@Builder

@ToString

@Entity

@Table(name="drivers")

public  class  Driver {

@Id

@GeneratedValue(strategy=GenerationType.AUTO)

@Column(name="id")

private  Integer  id;

@Column(unique =  true)

private  String  unid;

@Column

private  String  firstName;

@Column

private  String  lastName;

@Column

private  String  middleInitial;

@Column(unique =  true)

private  String  personId;

@Column

private  LocalDateTime  effectiveDate;

@Column

private  LocalDateTime  expirationDate;

@Column

private  String  status;

  

@OneToMany(orphanRemoval=true,fetch =  FetchType.EAGER)

@JoinColumn( name="driverId", insertable =  false, updatable =  false)

private  Set&lt;CarrierDriver&gt; carrierDrivers;

  

}

  
  

package  com.example.demo;

  

import  java.time.LocalDateTime;

  

import  jakarta.persistence.Column;

import  jakarta.persistence.Entity;

import  jakarta.persistence.Id;

import  jakarta.persistence.IdClass;

import  jakarta.persistence.Table;

import  lombok.AllArgsConstructor;

import  lombok.Builder;

import  lombok.Getter;

import  lombok.NoArgsConstructor;

import  lombok.Setter;

import  lombok.ToString;

  

@Getter

@Setter

@NoArgsConstructor

@AllArgsConstructor

@Builder

@ToString

@Entity

@IdClass(CarrierDriverId.class)

@Table(name="carrier-assignments")

public  class  CarrierDriver {

@Id

@Column

private  Integer  badgeId;

@Id

@Column

private  String  carrierId;

@Column

private  Integer  driverId;

@Column

private  LocalDateTime  effectiveDate;

@Column

private  LocalDateTime  expirationDate;

@Column

private  String  status;

  

}

  

@Getter

@Setter

@NoArgsConstructor

@AllArgsConstructor

@Builder

@ToString

class  CarrierDriverId {

private  Integer  badgeId;

private  String  carrierId;

}
</code></pre>

