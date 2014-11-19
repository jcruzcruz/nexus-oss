/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2014 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.security.realms.tools

import org.sonatype.security.model.CPrivilege
import org.sonatype.security.model.CProperty
import org.sonatype.security.model.CRole
import org.sonatype.security.model.Configuration

import javax.inject.Singleton

/**
 * @since 3.0
 */
@Singleton
class StaticSecurityResource2
implements StaticSecurityResource
{
  @Override
  Configuration getConfiguration() {
    return new Configuration(
        version: Configuration.MODEL_VERSION,
        privileges: [
            new CPrivilege(
                id: '4-test',
                type: 'method',
                name: '4-test',
                description: '',
                properties: [
                    new CProperty(key: 'method', value: 'read'),
                    new CProperty(key: 'permission', value: '/some/path4/')
                ])
            ,
            new CPrivilege(
                id: '5-test',
                type: 'method',
                name: '5-test',
                description: '',
                properties: [
                    new CProperty(key: 'method', value: 'read'),
                    new CProperty(key: 'permission', value: '/some/path5/')
                ])
            ,
            new CPrivilege(
                id: '6-test',
                type: 'method',
                name: '6-test',
                description: '',
                properties: [
                    new CProperty(key: 'method', value: 'read'),
                    new CProperty(key: 'permission', value: '/some/path6/')
                ])
        ],
        roles: [
            new CRole(
                id: 'anon',
                name: '',
                description: '',
                privileges: ['4-test'],
                roles: ['other']
            )
            ,
            new CRole(
                id: 'other',
                name: 'Other Role',
                description: 'Other Role Description',
                privileges: ['6-test']
            )
        ]
    )
  }
}
