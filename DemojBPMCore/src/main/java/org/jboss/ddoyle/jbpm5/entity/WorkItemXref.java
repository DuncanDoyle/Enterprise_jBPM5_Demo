package org.jboss.ddoyle.jbpm5.entity;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

@Entity
@Table(name="workitemxref")
@SequenceGenerator(name="workItemXrefSeq", sequenceName="workitemxref_seq")
public class WorkItemXref {
	
	@Id
    @GeneratedValue(strategy = GenerationType.AUTO, generator="workItemXrefSeq")
    private Long id;
	
	private Long workitem_id;
	
	private String workitem_uuid;
	
	public WorkItemXref() {
	}
	
	public WorkItemXref(final Long workitemId, final String workitemUuid) {
		this.workitem_id = workitemId;
		this.workitem_uuid = workitemUuid;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getWorkitem_id() {
		return workitem_id;
	}

	public void setWorkitem_id(Long workitem_id) {
		this.workitem_id = workitem_id;
	}

	public String getWorkitem_uuid() {
		return workitem_uuid;
	}

	public void setWorkitem_uuid(String workitem_uuid) {
		this.workitem_uuid = workitem_uuid;
	}

}
