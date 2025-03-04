/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.extension.ftp.api.proxy;

/**
 * Groups FTP Proxy connection parameters
 *
 * @since 1.6.0
 */

public interface ProxySettings {

  String getHost();

  int getPort();

  String getUsername();

  String getPassword();

}
