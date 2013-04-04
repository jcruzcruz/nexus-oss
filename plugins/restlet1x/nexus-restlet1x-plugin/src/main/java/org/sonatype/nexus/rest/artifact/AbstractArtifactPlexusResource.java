/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2012 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.rest.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.shiro.subject.Subject;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.restlet.Context;
import org.restlet.data.Form;
import org.restlet.data.Reference;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.resource.Representation;
import org.restlet.resource.ResourceException;
import org.restlet.resource.Variant;
import org.sonatype.nexus.proxy.AccessDeniedException;
import org.sonatype.nexus.proxy.IllegalOperationException;
import org.sonatype.nexus.proxy.IllegalRequestException;
import org.sonatype.nexus.proxy.ItemNotFoundException;
import org.sonatype.nexus.proxy.NoSuchRepositoryException;
import org.sonatype.nexus.proxy.NoSuchResourceStoreException;
import org.sonatype.nexus.proxy.RemoteStorageTransportOverloadedException;
import org.sonatype.nexus.proxy.RepositoryNotAvailableException;
import org.sonatype.nexus.proxy.StorageException;
import org.sonatype.nexus.proxy.access.AccessManager;
import org.sonatype.nexus.proxy.item.StorageFileItem;
import org.sonatype.nexus.proxy.maven.ArtifactStoreHelper;
import org.sonatype.nexus.proxy.maven.ArtifactStoreRequest;
import org.sonatype.nexus.proxy.maven.MavenRepository;
import org.sonatype.nexus.proxy.maven.RepositoryPolicy;
import org.sonatype.nexus.proxy.maven.gav.Gav;
import org.sonatype.nexus.proxy.repository.Repository;
import org.sonatype.nexus.proxy.storage.UnsupportedStorageOperationException;
import org.sonatype.nexus.rest.AbstractNexusPlexusResource;
import org.sonatype.nexus.rest.AbstractResourceStoreContentPlexusResource;
import org.sonatype.nexus.rest.StorageFileItemRepresentation;
import org.sonatype.nexus.rest.model.ArtifactCoordinate;
import org.sonatype.security.SecuritySystem;

import com.google.common.base.Strings;

public abstract class AbstractArtifactPlexusResource
    extends AbstractNexusPlexusResource
{
    @Requirement
    private SecuritySystem securitySystem;

    /**
     * Centralized way to create ResourceStoreRequests, since we have to fill in various things in Request context, like
     * authenticated username, etc.
     * 
     * @param isLocal
     * @return
     */
    protected ArtifactStoreRequest getResourceStoreRequest( Request request, boolean localOnly, boolean remoteOnly,
                                                            String repositoryId, String g, String a, String v,
                                                            String p, String c, String e )
        throws ResourceException
    {
        if ( StringUtils.isBlank( p ) && StringUtils.isBlank( e ) )
        {
            // if packaging and extension is both blank, it is a bad request
            throw new ResourceException( Status.CLIENT_ERROR_BAD_REQUEST,
                "Deployment tried with both 'packaging' and/or 'extension' being empty! One of these values is mandatory!" );
        }

        MavenRepository mavenRepository = getMavenRepository( repositoryId );

        // if extension is not given, fall-back to packaging and apply mapper
        if ( StringUtils.isBlank( e ) )
        {
            e = mavenRepository.getArtifactPackagingMapper().getExtensionForPackaging( p );
        }

        // clean up the classifier
        if ( StringUtils.isBlank( c ) )
        {
            c = null;
        }

        Gav gav = new Gav( g, a, v, c, e, null, null, null, false, null, false, null );

        ArtifactStoreRequest result = new ArtifactStoreRequest( mavenRepository, gav, localOnly, remoteOnly );

        if ( getLogger().isDebugEnabled() )
        {
            getLogger().debug( "Created ArtifactStoreRequest request for " + result.getRequestPath() );
        }

        // stuff in the originating remote address
        result.getRequestContext().put( AccessManager.REQUEST_REMOTE_ADDRESS, getValidRemoteIPAddress( request ) );

        // stuff in the user id if we have it in request
        Subject subject = securitySystem.getSubject();
        if ( subject != null && subject.getPrincipal() != null )
        {
            result.getRequestContext().put( AccessManager.REQUEST_USER, subject.getPrincipal().toString() );
        }
        result.getRequestContext().put( AccessManager.REQUEST_AGENT, request.getClientInfo().getAgent() );

        // this is HTTPS, get the cert and stuff it too for later
        if ( request.isConfidential() )
        {
            result.getRequestContext().put( AccessManager.REQUEST_CONFIDENTIAL, Boolean.TRUE );

            List<?> certs = (List<?>) request.getAttributes().get( "org.restlet.https.clientCertificates" );

            if ( certs != null )
            {
                result.getRequestContext().put( AccessManager.REQUEST_CERTIFICATES, certs );
            }
        }

        // put the incoming URLs
        result.setRequestAppRootUrl( getContextRoot( request ).toString() );
        result.setRequestUrl( request.getOriginalRef().toString() );

        return result;
    }

    protected Model getPom( Variant variant, Request request, Response response )
        throws ResourceException
    {
        Form form = request.getResourceRef().getQueryAsForm();

        // TODO: enable only one section retrieval of POM, ie. only mailing lists, or team members

        String groupId = form.getFirstValue( "g" );

        String artifactId = form.getFirstValue( "a" );

        String version = form.getFirstValue( "v" );

        String repositoryId = form.getFirstValue( "r" );

        if ( groupId == null || artifactId == null || version == null || repositoryId == null )
        {
            throw new ResourceException( Status.CLIENT_ERROR_BAD_REQUEST );
        }

        ArtifactStoreRequest gavRequest =
            getResourceStoreRequest( request, false, false, repositoryId, groupId, artifactId, version, null, null,
                "pom" );

        gavRequest.setRequestLocalOnly( isLocal( request, gavRequest.getRequestPath() ) );

        try
        {
            MavenRepository mavenRepository = getMavenRepository( repositoryId );

            ArtifactStoreHelper helper = mavenRepository.getArtifactStoreHelper();

            InputStream pomContent = null;

            InputStreamReader ir = null;

            Model pom = null;

            try
            {
                StorageFileItem file = helper.retrieveArtifactPom( gavRequest );

                pomContent = file.getInputStream();

                MavenXpp3Reader reader = new MavenXpp3Reader();

                ir = new InputStreamReader( pomContent );

                pom = reader.read( ir );
            }
            finally
            {
                IOUtil.close( pomContent );

                IOUtil.close( ir );
            }

            return pom;

        }
        catch ( Exception e )
        {
            handleException( request, response, e );
        }

        return null;
    }

    protected Object getContent( Variant variant, boolean redirectTo, Request request, Response response )
        throws ResourceException
    {
        Form form = request.getResourceRef().getQueryAsForm();

        String groupId = form.getFirstValue( "g" );

        String artifactId = form.getFirstValue( "a" );

        String version = form.getFirstValue( "v" );

        String packaging = form.getFirstValue( "p" );

        String classifier = form.getFirstValue( "c" );

        String repositoryId = form.getFirstValue( "r" );

        String extension = form.getFirstValue( "e" );

        if ( groupId == null || artifactId == null || version == null || repositoryId == null )
        {
            throw new ResourceException( Status.CLIENT_ERROR_BAD_REQUEST );
        }

        // default the packaging
        if ( StringUtils.isBlank( packaging ) )
        {
            packaging = "jar";
        }

        ArtifactStoreRequest gavRequest =
            getResourceStoreRequest( request, false, false, repositoryId, groupId, artifactId, version, packaging,
                classifier, extension );

        gavRequest.setRequestLocalOnly( isLocal( request, gavRequest.getRequestPath() ) );

        try
        {
            MavenRepository mavenRepository = getMavenRepository( repositoryId );

            ArtifactStoreHelper helper = mavenRepository.getArtifactStoreHelper();

            StorageFileItem file = helper.retrieveArtifact( gavRequest );

            if ( redirectTo )
            {
                Reference fileReference =
                    createRepositoryReference( request, file.getRepositoryItemUid().getRepository().getId(),
                        file.getRepositoryItemUid().getPath() );

                response.setLocationRef( fileReference );

                response.setStatus( Status.REDIRECTION_TEMPORARY );

                String redirectMessage =
                    "If you are not automatically redirected use this url: " + fileReference.toString();
                return redirectMessage;
            }
            else
            {
                Representation result = new StorageFileItemRepresentation( file );

                result.setDownloadable( true );

                result.setDownloadName( file.getName() );

                return result;
            }

        }
        catch ( Exception e )
        {
            handleException( request, response, e );
        }

        return null;
    }

    @Override
    public Object upload( Context context, Request request, Response response, List<FileItem> files )
        throws ResourceException
    {
        // Every file selected for upload will generate a SEPARATE upload HTTP POST request
        // that is multipart form upload basically.
        // Each upload request has form like: (params, file1, [file2]).
        // we have two cases, either POM is present for upload too, or user filled in GAVP
        // fields in upload form and wants us to generate a POM for him.
        // Params ALWAYS have param "r"=repoId as first element (unless we talk about staging, where this is NOT the
        // case)

        // First case, when POM is present (POM file is selected), file1 is always POM
        // and file2 is the (main or classified) artifact (UI does not validate, but cases when packaging=pom,
        // or user error when packaging=jar but no main artifact selected are possible!)
        // params are then "r", "hasPom"=TRUE, "c"=C, "e"=E
        // Interestingly, subsequent requests (those for "extra" artifacts with classifiers
        // follows the "second case" pattern below, as they get GAV from ArtifactCoordinate response.
        // The "c" param if null means file2 is classified artifact or main artifact.
        // The "e" param might be null, if pom is the only upload (ie. packaging=pom)
        // Still, in case POM.packaging=pom plus one classified artifact, parameters
        // "c" and "e" will belong to the classified artifact!

        // Second case, when POM is not present (we need to generate it), user has to
        // fill in the GAVP fields in form on UI. In that case, nibble is always in form of
        // (params, file1), so it will strictly have only one file appended as last content part
        // of multipart form upload, where params will contain "extra" fields:
        // "r"=repoId, "g"=G, "a"=A, "v"=V, "p"=P, "c"=C, "e"=E

        String repositoryId = null;
        boolean hasPom = false;
        String extension = null;
        String classifier = null;
        ArtifactCoordinate coords = null;

        PomArtifactManager pomManager =
            new PomArtifactManager( getNexus().getNexusConfiguration().getTemporaryDirectory() );

        try
        {
            String groupId = null;
            String artifactId = null;
            String version = null;
            String packaging = null;

            for ( FileItem fi : files )
            {
                if ( fi.isFormField() )
                {
                    // a parameter
                    if ( "r".equals( fi.getFieldName() ) )
                    {
                        repositoryId = fi.getString();
                    }
                    else if ( "g".equals( fi.getFieldName() ) )
                    {
                        groupId = fi.getString();
                    }
                    else if ( "a".equals( fi.getFieldName() ) )
                    {
                        artifactId = fi.getString();
                    }
                    else if ( "v".equals( fi.getFieldName() ) )
                    {
                        version = fi.getString();
                    }
                    else if ( "p".equals( fi.getFieldName() ) )
                    {
                        packaging = fi.getString();
                    }
                    else if ( "c".equals( fi.getFieldName() ) )
                    {
                        classifier = fi.getString();
                    }
                    else if ( "e".equals( fi.getFieldName() ) )
                    {
                        extension = fi.getString();
                    }
                    else if ( "hasPom".equals( fi.getFieldName() ) )
                    {
                        hasPom = Boolean.parseBoolean( fi.getString() );
                    }

                    // create it once we have all, and only if needed
                    if ( !hasPom && coords == null && !Strings.isNullOrEmpty( groupId )
                        && !Strings.isNullOrEmpty( artifactId ) && !Strings.isNullOrEmpty( version )
                        && !Strings.isNullOrEmpty( packaging ) )
                    {
                        // repositoryId might be null (staging)
                        coords = new ArtifactCoordinate();
                        coords.setGroupId( groupId );
                        coords.setArtifactId( artifactId );
                        coords.setVersion( version );
                        coords.setPackaging( packaging );
                        uploadGavParametersAvailable( request, repositoryId, coords );
                    }
                }
                else
                {
                    // a file
                    boolean isPom = fi.getName().endsWith( ".pom" ) || fi.getName().endsWith( "pom.xml" );
                    InputStream is = null;

                    ArtifactStoreRequest gavRequest = null;

                    if ( hasPom )
                    {
                        if ( isPom )
                        {
                            // let it "thru" the pomManager to be able to get GAV from it on later pass
                            pomManager.storeTempPomFile( fi.getInputStream() );
                            is = pomManager.getTempPomFileInputStream();
                        }
                        else
                        {
                            is = fi.getInputStream();
                        }

                        try
                        {
                            coords = pomManager.getArtifactCoordinateFromTempPomFile();
                            if ( isPom )
                            {
                                uploadGavParametersAvailable( request, repositoryId, coords );
                            }
                        }
                        catch ( IOException e )
                        {
                            getLogger().info( "Error occurred while reading the POM file. Malformed POM?", e );
                            throw new ResourceException( Status.CLIENT_ERROR_BAD_REQUEST,
                                "Error occurred while reading the POM file. Malformed POM?", e );
                        }

                        if ( isPom )
                        {
                            gavRequest =
                                getResourceStoreRequest( request, true, false, repositoryId, coords.getGroupId(),
                                    coords.getArtifactId(), coords.getVersion(), coords.getPackaging(), null, null );
                        }
                        else
                        {
                            gavRequest =
                                getResourceStoreRequest( request, true, false, repositoryId, coords.getGroupId(),
                                    coords.getArtifactId(), coords.getVersion(), coords.getPackaging(), classifier,
                                    extension );
                        }
                    }
                    else
                    {
                        is = fi.getInputStream();
                        gavRequest =
                            getResourceStoreRequest( request, true, false, repositoryId, coords.getGroupId(),
                                coords.getArtifactId(), coords.getVersion(), coords.getPackaging(), classifier,
                                extension );
                    }

                    final MavenRepository mr = gavRequest.getMavenRepository();
                    final ArtifactStoreHelper helper = mr.getArtifactStoreHelper();

                    // temporarily we disable SNAPSHOT upload
                    // check is it a Snapshot repo
                    if ( RepositoryPolicy.SNAPSHOT.equals( mr.getRepositoryPolicy() ) )
                    {
                        getLogger().info( "Upload to SNAPSHOT maven repository {} attempted, returning Bad Request.",
                            mr );
                        throw new ResourceException( Status.CLIENT_ERROR_BAD_REQUEST,
                            "This is a Maven SNAPSHOT repository, and manual upload against it is forbidden!" );
                    }

                    if ( !versionMatchesPolicy( gavRequest.getVersion(), mr.getRepositoryPolicy() ) )
                    {
                        getLogger().info( "Artifact version {} and {} Repository Policy {} mismatch",
                            gavRequest.getVersion(), mr, mr.getRepositoryPolicy() );
                        throw new ResourceException( Status.CLIENT_ERROR_BAD_REQUEST, "The version "
                            + gavRequest.getVersion() + " does not match the repository policy!" );
                    }

                    if ( isPom )
                    {
                        helper.storeArtifactPom( gavRequest, is, null );
                        isPom = false;
                    }
                    else
                    {
                        if ( hasPom )
                        {
                            helper.storeArtifact( gavRequest, is, null );
                        }
                        else
                        {
                            helper.storeArtifactWithGeneratedPom( gavRequest, coords.getPackaging(), is, null );
                        }
                    }
                }
            }
        }
        catch ( Exception t )
        {
            return buildUploadFailedHtmlResponse( t, request, response );
        }
        finally
        {
            if ( hasPom )
            {
                pomManager.removeTempPomFile();
            }
        }

        return coords;
    }

    /**
     * Invoked once from upload method, when all the coordinates are ready (either all form params are processed or POM
     * is parsed).
     * 
     * @param request
     * @param repositoryId
     * @param coords
     * @throws ResourceException
     */
    protected void uploadGavParametersAvailable( final Request request, final String repositoryId,
                                                 final ArtifactCoordinate coords )
        throws ResourceException
    {
        // nop
    }

    protected String buildUploadFailedHtmlResponse( Throwable t, Request request, Response response )
    {
        try
        {
            handleException( request, response, t );
        }
        catch ( ResourceException e )
        {
            getLogger().debug( "Got error while uploading artifact", t );

            StringBuilder resp = new StringBuilder();
            resp.append( "<html><body><error>" );
            resp.append( StringEscapeUtils.escapeHtml( e.getMessage() ) );
            resp.append( "</error></body></html>" );

            String forceSuccess = request.getResourceRef().getQueryAsForm().getFirstValue( "forceSuccess" );

            if ( !"true".equals( forceSuccess ) )
            {
                response.setStatus( e.getStatus() );
            }

            return resp.toString();
        }

        // We have an error at this point, can't get here
        return null;
    }

    protected void handleException( Request request, Response res, Throwable t )
        throws ResourceException
    {
        if ( t instanceof ResourceException )
        {
            throw (ResourceException) t;
        }
        else if ( t instanceof IllegalArgumentException )
        {
            getLogger().info( "ResourceStoreContentResource, illegal argument:" + t.getMessage() );

            throw new ResourceException( Status.CLIENT_ERROR_BAD_REQUEST, t.getMessage() );
        }
        else if ( t instanceof RemoteStorageTransportOverloadedException )
        {
            throw new ResourceException( Status.SERVER_ERROR_SERVICE_UNAVAILABLE, t );
        }
        else if ( t instanceof RepositoryNotAvailableException )
        {
            throw new ResourceException( Status.SERVER_ERROR_SERVICE_UNAVAILABLE, t.getMessage() );
        }
        else if ( t instanceof IllegalRequestException )
        {
            getLogger().info( "ResourceStoreContentResource, illegal request:" + t.getMessage() );

            throw new ResourceException( Status.CLIENT_ERROR_BAD_REQUEST, t.getMessage() );
        }
        else if ( t instanceof IllegalOperationException )
        {
            getLogger().info( "ResourceStoreContentResource, illegal operation:" + t.getMessage() );

            throw new ResourceException( Status.CLIENT_ERROR_BAD_REQUEST, t.getMessage() );
        }
        else if ( t instanceof StorageException )
        {
            getLogger().warn( "IO problem!", t );

            throw new ResourceException( Status.SERVER_ERROR_INTERNAL, t.getMessage() );
        }
        else if ( t instanceof UnsupportedStorageOperationException )
        {
            throw new ResourceException( Status.CLIENT_ERROR_FORBIDDEN, t.getMessage() );
        }
        else if ( t instanceof NoSuchResourceStoreException )
        {
            throw new ResourceException( Status.CLIENT_ERROR_NOT_FOUND, t.getMessage() );
        }
        else if ( t instanceof ItemNotFoundException )
        {
            throw new ResourceException( Status.CLIENT_ERROR_NOT_FOUND, t.getMessage() );
        }
        else if ( t instanceof AccessDeniedException )
        {
            AbstractResourceStoreContentPlexusResource.challengeIfNeeded( request, res, (AccessDeniedException) t );
            if ( Status.CLIENT_ERROR_FORBIDDEN.equals( res.getStatus() ) )
            {
                throw new ResourceException( Status.CLIENT_ERROR_FORBIDDEN, t.getMessage() );
            }
        }
        else if ( t instanceof XmlPullParserException )
        {
            throw new ResourceException( Status.CLIENT_ERROR_BAD_REQUEST, t.getMessage() );
        }
        else if ( t instanceof IOException )
        {
            getLogger().warn( "IO error!", t );

            throw new ResourceException( Status.SERVER_ERROR_INTERNAL, t.getMessage() );
        }
        else
        {
            getLogger().warn( t.getMessage(), t );

            throw new ResourceException( Status.SERVER_ERROR_INTERNAL, t.getMessage() );
        }
    }

    protected MavenRepository getMavenRepository( String id )
        throws ResourceException
    {
        try
        {
            Repository repository = getUnprotectedRepositoryRegistry().getRepository( id );

            if ( !repository.getRepositoryKind().isFacetAvailable( MavenRepository.class ) )
            {
                throw new ResourceException( Status.CLIENT_ERROR_BAD_REQUEST, "This is not a Maven repository!" );
            }

            return repository.adaptToFacet( MavenRepository.class );
        }
        catch ( NoSuchRepositoryException e )
        {
            throw new ResourceException( Status.CLIENT_ERROR_NOT_FOUND, e.getMessage(), e );
        }
    }

    protected boolean versionMatchesPolicy( String version, RepositoryPolicy policy )
    {
        boolean result = false;

        if ( ( RepositoryPolicy.SNAPSHOT.equals( policy ) && Gav.isSnapshot( version ) )
            || ( RepositoryPolicy.RELEASE.equals( policy ) && !Gav.isSnapshot( version ) ) )
        {
            result = true;
        }

        return result;
    }

}
