package graphql.schema.impl;


import com.google.common.collect.ImmutableMap;
import graphql.Internal;
import graphql.schema.CodeRegistryVisitor;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLImplementingType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeResolvingVisitor;
import graphql.schema.GraphQLTypeVisitor;
import graphql.schema.SchemaTraverser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Internal
public class SchemaUtil {

    private static final SchemaTraverser TRAVERSER = new SchemaTraverser();

    /**
     * Called to visit a partially build schema (during {@link GraphQLSchema} build phases) with a set of visitors
     *
     * Each visitor is expected to hold its own side effects that might be last used to construct a full schema
     *
     * @param partiallyBuiltSchema the partially built schema
     * @param visitors             the visitors to call
     */

    public static void visitPartiallySchema(final GraphQLSchema partiallyBuiltSchema, GraphQLTypeVisitor... visitors) {
        List<GraphQLSchemaElement> roots = new ArrayList<>();
        roots.add(partiallyBuiltSchema.getQueryType());

        if (partiallyBuiltSchema.isSupportingMutations()) {
            roots.add(partiallyBuiltSchema.getMutationType());
        }

        if (partiallyBuiltSchema.isSupportingSubscriptions()) {
            roots.add(partiallyBuiltSchema.getSubscriptionType());
        }

        if (partiallyBuiltSchema.getAdditionalTypes() != null) {
            roots.addAll(partiallyBuiltSchema.getAdditionalTypes());
        }

        if (partiallyBuiltSchema.getDirectives() != null) {
            roots.addAll(partiallyBuiltSchema.getDirectives());
        }

        roots.add(partiallyBuiltSchema.getIntrospectionSchemaType());

        GraphQLTypeVisitor visitor = new MultiReadOnlyGraphQLTypeVisitor(Arrays.asList(visitors));
        SchemaTraverser traverser;
        traverser = new SchemaTraverser(schemaElement -> schemaElement.getChildrenWithTypeReferences().getChildrenAsList());
        traverser.depthFirst(visitor, roots);
    }

    public ImmutableMap<String, GraphQLNamedType> allTypes(final GraphQLSchema schema, final Set<GraphQLType> additionalTypes, boolean afterTransform) {
        List<GraphQLSchemaElement> roots = new ArrayList<>();
        roots.add(schema.getQueryType());

        if (schema.isSupportingMutations()) {
            roots.add(schema.getMutationType());
        }

        if (schema.isSupportingSubscriptions()) {
            roots.add(schema.getSubscriptionType());
        }

        if (additionalTypes != null) {
            roots.addAll(additionalTypes);
        }

        if (schema.getDirectives() != null) {
            roots.addAll(schema.getDirectives());
        }

        roots.add(schema.getIntrospectionSchemaType());

        GraphQLTypeCollectingVisitor visitor = new GraphQLTypeCollectingVisitor();
        SchemaTraverser traverser;
        // when collecting all types we never want to follow type references
        // When a schema is build first the type references are not replaced, so
        // this is not a problem. But when a schema is transformed,
        // the type references are actually replaced so we need to make sure we
        // use the original type references
        if (afterTransform) {
            traverser = new SchemaTraverser(schemaElement -> schemaElement.getChildrenWithTypeReferences().getChildrenAsList());
        } else {
            traverser = new SchemaTraverser();
        }
        traverser.depthFirst(visitor, roots);
        Map<String, GraphQLNamedType> result = visitor.getResult();
        return ImmutableMap.copyOf(new TreeMap<>(result));
    }


    /*
     * Indexes GraphQLObject types registered with the provided schema by implemented GraphQLInterface name
     *
     * This helps in accelerates/simplifies collecting types that implement a certain interface
     *
     * Provided to replace {@link #findImplementations(graphql.schema.GraphQLSchema, graphql.schema.GraphQLInterfaceType)}
     *
     */
    public Map<String, List<GraphQLObjectType>> groupImplementations(GraphQLSchema schema) {
        List<GraphQLNamedType> allTypesAsList = schema.getAllTypesAsList();
        return groupInterfaceImplementationsByName(allTypesAsList);
    }

    public ImmutableMap<String, List<GraphQLObjectType>> groupInterfaceImplementationsByName(List<GraphQLNamedType> allTypesAsList) {
        Map<String, List<GraphQLObjectType>> result = new LinkedHashMap<>();
        for (GraphQLType type : allTypesAsList) {
            if (type instanceof GraphQLObjectType) {
                List<GraphQLNamedOutputType> interfaces = ((GraphQLObjectType) type).getInterfaces();
                for (GraphQLNamedOutputType interfaceType : interfaces) {
                    List<GraphQLObjectType> myGroup = result.computeIfAbsent(interfaceType.getName(), k -> new ArrayList<>());
                    myGroup.add((GraphQLObjectType) type);
                }
            }
        }
        return ImmutableMap.copyOf(new TreeMap<>(result));
    }

    public Map<String, List<GraphQLImplementingType>> groupImplementationsForInterfacesAndObjects(GraphQLSchema schema) {
        Map<String, List<GraphQLImplementingType>> result = new LinkedHashMap<>();
        for (GraphQLType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLImplementingType) {
                List<GraphQLNamedOutputType> interfaces = ((GraphQLImplementingType) type).getInterfaces();
                for (GraphQLNamedOutputType interfaceType : interfaces) {
                    List<GraphQLImplementingType> myGroup = result.computeIfAbsent(interfaceType.getName(), k -> new ArrayList<>());
                    myGroup.add((GraphQLImplementingType) type);
                }
            }
        }
        return ImmutableMap.copyOf(new TreeMap<>(result));
    }

    /**
     * This method is deprecated due to a performance concern.
     *
     * The Algorithm complexity: O(n^2), where n is number of registered GraphQLTypes
     *
     * That indexing operation is performed twice per input document:
     * 1. during validation
     * 2. during execution
     *
     * We now indexed all types at the schema creation, which has brought complexity down to O(1)
     *
     * @param schema        GraphQL schema
     * @param interfaceType an interface type to find implementations for
     *
     * @return List of object types implementing provided interface
     *
     * @deprecated use {@link graphql.schema.GraphQLSchema#getImplementations(GraphQLInterfaceType)} instead
     */
    @Deprecated
    public List<GraphQLObjectType> findImplementations(GraphQLSchema schema, GraphQLInterfaceType interfaceType) {
        List<GraphQLObjectType> result = new ArrayList<>();
        for (GraphQLType type : schema.getAllTypesAsList()) {
            if (!(type instanceof GraphQLObjectType)) {
                continue;
            }
            GraphQLObjectType objectType = (GraphQLObjectType) type;
            if ((objectType).getInterfaces().contains(interfaceType)) {
                result.add(objectType);
            }
        }
        return result;
    }

    // THIS WILL BE REMOVED
    public void replaceTypeReferences(GraphQLSchema schema) {
        final Map<String, GraphQLNamedType> typeMap = schema.getTypeMap();
        replaceTypeReferences(schema, typeMap);
    }

    public void replaceTypeReferences(GraphQLSchema schema, Map<String, GraphQLNamedType> typeMap) {
        List<GraphQLSchemaElement> roots = new ArrayList<>(typeMap.values());
        roots.addAll(schema.getDirectives());
        SchemaTraverser schemaTraverser = new SchemaTraverser(schemaElement -> schemaElement.getChildrenWithTypeReferences().getChildrenAsList());
        schemaTraverser.depthFirst(new GraphQLTypeResolvingVisitor(typeMap), roots);
    }

    // THIS WILL BE REMOVED
    public void extractCodeFromTypes(GraphQLCodeRegistry.Builder codeRegistry, GraphQLSchema schema) {
        TRAVERSER.depthFirst(new CodeRegistryVisitor(codeRegistry), schema.getAllTypesAsList());
    }
}