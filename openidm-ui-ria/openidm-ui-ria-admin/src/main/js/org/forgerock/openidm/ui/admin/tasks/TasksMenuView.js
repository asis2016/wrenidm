/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2012 ForgeRock AS. All rights reserved.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */

/*global define, $, form2js, _, Backbone */

/**
 * @author mbilski
 */
define("org/forgerock/openidm/ui/admin/tasks/TasksMenuView", [
    "org/forgerock/openidm/ui/admin/tasks/WorkflowDelegate",
    "org/forgerock/commons/ui/common/main/EventManager",
    "org/forgerock/commons/ui/common/util/Constants",
    "dataTable",
    "org/forgerock/commons/ui/common/main/Configuration",
    "org/forgerock/commons/ui/common/util/UIUtils",
    "org/forgerock/openidm/ui/apps/delegates/UserApplicationLnkDelegate",
    "org/forgerock/commons/ui/user/delegates/UserDelegate",
    "org/forgerock/openidm/ui/apps/delegates/ApplicationDelegate"
], function(workflowManager, eventManager, constants, dataTable, conf, uiUtils, userApplicationLnkDelegate, userDelegate, applicationDelegate) {
    var TasksMenuView = Backbone.View.extend({
        
        events: {
            "click tr": "showTask",
            "click .claimLink": "claimTask",
            "click .approveLink": "approveTask",
            "click .denyLink" : "denyTask",
            "click .requeueLink": "requeueTask"
        },
        
        showTask: function(event) {
            var id = $(event.target).parent().find("input[type=hidden]").val();
            
            if(id) {
                eventManager.sendEvent("showTaskDetailsRequest", {"category": this.category, "id": id});
            }
        },
        
        render: function(category, element, callback) {
            if(!element) {
                this.setElement($("#tasksMenu"));
            } else {
                this.setElement(element);
            }
            
            this.$el.html('');
            
            this.category = category;
            
            if(category === "all") {
                workflowManager.getAllAvalibleTasksViewForUser(conf.loggedUser.userName, _.bind(this.displayTasks, this), _.bind(this.errorHandler, this));
            } else if(category === "assigned") {
                workflowManager.getAllTasksViewForUser(conf.loggedUser.userName, _.bind(this.displayTasks, this), _.bind(this.errorHandler, this));
            }
        },
        
        errorHandler: function() {
            this.$el.append('<b>No tasks</b>');
        },
        
        displayTasks: function(tasks) {
            var process, data, processName, task, taskName, i, j, params, actions;
            
            actions = this.getActions();     
            
            for(processName in tasks) {
                process = tasks[processName];
                
                for(taskName in process) {
                    task = process[taskName];
                    
                    data = {
                        processName: processName,
                        taskName: taskName,
                        count: task.tasks.length,
                        //TODO make it generic
                        headers: ["For", "Application", "Requested", "Actions"],
                        tasks: []
                    };
                    
                    j = 0;
                    for(i = 0; i < task.tasks.length; i++) {
                        params = task.tasks[i];
                        
                        if(params.userApplicationLnkId) {
                            //data.tasks.push(this.prepareParams(params));
                            this.fetchParameters(params.userApplicationLnkId, params._id, _.bind(function(userName, appName, date, taskId) {
                                //TODO make it generic
                                data.tasks.push(this.prepareParams({"user": userName, "app": appName, "date": date, "actions": actions, "_id": taskId}));
                                j++;
                                
                                if(j === task.tasks.length) {
                                    this.$el.append(uiUtils.fillTemplateWithData("templates/admin/tasks/ProcessUserTaskTableTemplate.html", data));
                                    this.$el.accordion({collapsible: true, fillSpace: true});
                                }
                            }, this));
                        } 
                    }    
                }    
            }          
        },
        
        fetchParameters: function(userAppLinkId, taskId, callback) {
            userApplicationLnkDelegate.readEntity(userAppLinkId, function(userAppLink) {                
                userDelegate.readEntity(userAppLink.userId, function(user) {
                    applicationDelegate.getApplicationDetails(userAppLink.applicationId, function(app) {
                        callback(user.givenName + ' ' + user.familyName, app.name, userAppLink.lastTimeUsed, taskId);
                    });
                });   
            });
        },
        
        getActions: function() {
            if(this.category === 'all') {
                return '<a href="#" class="buttonOrange claimLink">Claim</a>';
            } else if(this.category === 'assigned') {
                return '<a href="#" class="buttonOrange approveLink">Approve</a>' +
                    '<a href="#" class="buttonOrange denyLink">Deny</a>' +
                    '<a href="#" class="buttonOrange requeueLink">Requeue</a>';
            }
        },
        
        claimTask: function(event) {
            event.preventDefault();
            
            var id = $(event.target).parent().parent().find("input[type=hidden]").val();

            if(id) {
                workflowManager.assignTaskToUser(id, conf.loggedUser.userName, _.bind(function() {
                   eventManager.sendEvent(constants.EVENT_DISPLAY_MESSAGE_REQUEST, "claimedTask");
                   eventManager.sendEvent("refreshTasksMenu");
                }, this), function() {
                    eventManager.sendEvent(constants.EVENT_DISPLAY_MESSAGE_REQUEST, "unknown");
                });
            }
        },
        
        approveTask: function(event) {
            event.preventDefault();
            
            var id = $(event.target).parent().parent().find("input[type=hidden]").val();
            
            if(id) {
                workflowManager.completeTask(id, {"decision": "accept"}, _.bind(function() {
                    eventManager.sendEvent(constants.EVENT_DISPLAY_MESSAGE_REQUEST, "completedTask");
                    eventManager.sendEvent("refreshTasksMenu");
                }, this), function() {
                    eventManager.sendEvent(constants.EVENT_DISPLAY_MESSAGE_REQUEST, "unknown");
                });
            }
        },
        
        denyTask: function(event) {
            event.preventDefault();
            
            var id = $(event.target).parent().parent().find("input[type=hidden]").val();
            
            if(id) {
                workflowManager.completeTask(id, {"decision": "reject"}, _.bind(function() {
                    eventManager.sendEvent(constants.EVENT_DISPLAY_MESSAGE_REQUEST, "completedTask");
                    eventManager.sendEvent("refreshTasksMenu");
                }, this), function() {
                    eventManager.sendEvent(constants.EVENT_DISPLAY_MESSAGE_REQUEST, "unknown");
                });
            }
        },
        
        requeueTask: function(event) {
            event.preventDefault();
            
            var id = $(event.target).parent().parent().find("input[type=hidden]").val();

            if(id) {
                workflowManager.assignTaskToUser(id, "", _.bind(function() {
                   eventManager.sendEvent(constants.EVENT_DISPLAY_MESSAGE_REQUEST, "unclaimedTask");
                   eventManager.sendEvent("refreshTasksMenu");
                }, this), function() {
                    eventManager.sendEvent(constants.EVENT_DISPLAY_MESSAGE_REQUEST, "unknown");
                });
            }
        },
        
        prepareParams: function(params) {
            return $.map(params, function(v, k) {
                if( k === "_id" ) {
                    return '<input type="hidden" value="'+ v +'" />';
                }
                
                return v;
            });
        }
    }); 
    
    return TasksMenuView;
});


