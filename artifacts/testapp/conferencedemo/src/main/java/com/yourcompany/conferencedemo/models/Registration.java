package com.yourcompany.conferencedemo.models;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import java.util.Date;

import javax.persistence.NamedStoredProcedureQuery;
import javax.persistence.StoredProcedureParameter;
import javax.persistence.ParameterMode;

@Entity(name="REGISTRATIONS")
@NamedStoredProcedureQuery(
    name="registerAttendeeSession",
    procedureName="reg_app.register_attendee_session",
    parameters = {
        @StoredProcedureParameter(mode=ParameterMode.IN, type=Integer.class, name="P_SESSION_ID"),
        @StoredProcedureParameter(mode=ParameterMode.IN, type=Integer.class, name="P_ATTENDEE_ID"),
        @StoredProcedureParameter(mode=ParameterMode.OUT, type=Integer.class, name="STATUS")
    }
)
public class Registration {

    // You need to specify the identifier for this entity.
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Integer id;
    private Date registrationDate;
    private Integer sessionId;
    private Integer attendeeId;


    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Date getRegistrationDate() {
        return registrationDate;
    }

    public void setRegistrationDate(Date registrationDate) {
        this.registrationDate = registrationDate;
    }

    public Integer getSessionId() {
        return sessionId;
    }

    public void setSessionId(Integer sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getAttendeeId() {
        return attendeeId;
    }

    public void setAttendeeId(Integer attendeeId) {
        this.attendeeId = attendeeId;
    }
}
