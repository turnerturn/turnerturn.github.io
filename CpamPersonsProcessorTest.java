package com.example.demo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.core.env.Environment;


public class CpamPersonsProcessorTest {

    @Mock
    private Environment env;

    @Mock
    private DriverService driverService;

    @InjectMocks
    private CpamPersonsProcessor cpamPersonsProcessor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        cpamPersonsProcessor.setHostname("test-host");
        cpamPersonsProcessor.setAccessLevels(new HashMap<>() {{
            put("test-host", "test-access-level");
        }});
    }

    @Test
    void testProcessWhenCpamPersonProcessorDisabled() {
        when(env.getProperty("features.cpam-person-processor", Boolean.class, false)).thenReturn(false);

        cpamPersonsProcessor.process(Collections.emptyList());

        verify(driverService, never()).driverExistsByCpamPerson(any());
    }

    @Test
    void testProcessWhenMergeCarrierAssignmentsFromReassignedDriversEnabled() {
        when(env.getProperty("features.cpam-person-processor", Boolean.class, false)).thenReturn(true);
        when(env.getProperty("features.reassign-carrier-assignments-from-reassigned-drivers", Boolean.class, false)).thenReturn(true);
        when(env.getProperty("features.delete-carrier-assignments-with-reassigned-drivers", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.delete-carrier-assignments-with-insufficient-accesslevel", Boolean.class, false)).thenReturn(true);
        when(env.getProperty("features.delete-carrier-assignments-with-unassigned-badges", Boolean.class, false)).thenReturn(true);
        when(env.getProperty("features.swift-dispatching", Boolean.class, false)).thenReturn(false);

        CpamPerson cpamPerson = new CpamPerson();
        cpamPerson.setBadges(Collections.singletonList(new Badge(1, "active", LocalDateTime.now(), LocalDateTime.now().plusDays(1), Collections.singletonList(new AccessLevel("","test-access-level")))));
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
    void testProcessWhenDeleteCarrierAssignmentsWithReassignedDriversEnabled() {
        when(env.getProperty("features.cpam-person-processor", Boolean.class, false)).thenReturn(true);
        when(env.getProperty("features.reassign-carrier-assignments-from-reassigned-drivers", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.delete-carrier-assignments-with-reassigned-drivers", Boolean.class, false)).thenReturn(true);
        when(env.getProperty("features.delete-carrier-assignments-with-insufficient-accesslevel", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.delete-carrier-assignments-with-unassigned-badges", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.swift-dispatching", Boolean.class, false)).thenReturn(false);

        CpamPerson cpamPerson = new CpamPerson();
        cpamPerson.setBadges(Collections.singletonList(new Badge(1, "active", LocalDateTime.now(), LocalDateTime.now().plusDays(1), Collections.singletonList(new AccessLevel("","test-access-level")))));
        cpamPerson.setUnid("test-unid");
        cpamPerson.getBadges().get(0).setCardNumber(1);
        when(driverService.driverExistsByCpamPerson(any( ))).thenReturn(false);
        when(driverService.findDriverIdByCpamPerson(any( ))).thenReturn(Optional.of(1));
        when(driverService.findBadgeNumbersByDriverId(1)).thenReturn(Collections.singleton(1));
        when(driverService.findDriverIdsByBadge(cpamPerson.getBadges().get(0).getCardNumber())).thenReturn(new HashSet<>(Arrays.asList(1, 2)));
        cpamPersonsProcessor.process(Collections.singletonList(cpamPerson));

        verify(driverService).insertDriverByCpamPerson(cpamPerson);
        verify(driverService,atLeastOnce()).deleteCarrierDriversWithReassignedBadge(any(), any());
    }

    @Test
    void testProcessWhenDeleteCarrierAssignmentsWithInsufficientAccessLevelEnabled() {
        when(env.getProperty("features.cpam-person-processor", Boolean.class, false)).thenReturn(true);
        when(env.getProperty("features.reassign-carrier-assignments-from-reassigned-drivers", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.delete-carrier-assignments-with-reassigned-drivers", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.delete-carrier-assignments-with-insufficient-accesslevel", Boolean.class, false)).thenReturn(true);
        when(env.getProperty("features.delete-carrier-assignments-with-unassigned-badges", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.swift-dispatching", Boolean.class, false)).thenReturn(false);

        CpamPerson cpamPerson = new CpamPerson();
        cpamPerson.setBadges(Collections.singletonList(new Badge(1, "active", LocalDateTime.now(), LocalDateTime.now().plusDays(1), Collections.singletonList(new AccessLevel("","wrong-access-level")))));
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
    void testProcessWhenDeleteCarrierAssignmentsWithUnassignedBadgesEnabled() {
        when(env.getProperty("features.cpam-person-processor", Boolean.class, false)).thenReturn(true);
        when(env.getProperty("features.reassign-carrier-assignments-from-reassigned-drivers", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.delete-carrier-assignments-with-reassigned-drivers", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.delete-carrier-assignments-with-insufficient-accesslevel", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.delete-carrier-assignments-with-unassigned-badges", Boolean.class, false)).thenReturn(true);
        when(env.getProperty("features.swift-dispatching", Boolean.class, false)).thenReturn(false);

        CpamPerson cpamPerson = new CpamPerson();
        cpamPerson.setBadges(Collections.singletonList(new Badge(1, "active", LocalDateTime.now(), LocalDateTime.now().plusDays(1), Collections.singletonList(new AccessLevel("","test-access-level")))));
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
    void testProcessWhenSwiftDispatchingEnabled() {
        when(env.getProperty("features.cpam-person-processor", Boolean.class, false)).thenReturn(true);
        when(env.getProperty("features.reassign-carrier-assignments-from-reassigned-drivers", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.delete-carrier-assignments-with-reassigned-drivers", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.delete-carrier-assignments-with-insufficient-accesslevel", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.delete-carrier-assignments-with-unassigned-badges", Boolean.class, false)).thenReturn(false);
        when(env.getProperty("features.swift-dispatching", Boolean.class, false)).thenReturn(true);

        CpamPerson cpamPerson = new CpamPerson();
        cpamPerson.setBadges(Collections.singletonList(new Badge(1, "active", LocalDateTime.now(), LocalDateTime.now().plusDays(1), Collections.singletonList(new AccessLevel("","test-access-level")))));
        cpamPerson.setUnid("test-unid");

        when(driverService.driverExistsByCpamPerson(cpamPerson)).thenReturn(false);
        when(driverService.findDriverIdByCpamPerson(cpamPerson)).thenReturn(Optional.of(1));
        when(driverService.findBadgeNumbersByDriverId(1)).thenReturn(Collections.singleton(1));
        when(driverService.findDriverIdsByBadge(any())).thenReturn(Collections.singleton(2));
        when(driverService.findDriverById(1)).thenReturn(Optional.of(new Driver()));

        cpamPersonsProcessor.process(Collections.singletonList(cpamPerson));

        verify(driverService).insertDriverByCpamPerson(cpamPerson);
        verify(driverService).publishSwiftDispatchingEvent(any());
    }
}


