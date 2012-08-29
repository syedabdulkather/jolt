package com.bazaarvoice.jolt;

import com.bazaarvoice.jolt.helpers.DefaultrKey;
import com.bazaarvoice.jolt.helpers.DefaultrKey.DefaultrKeyComparator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Defaultr is a kind of JOLT transform that applies default values in a non-destructive way.
 *
 * For comparision :
 * Shitr walks the input data and asks its spec "Where should this go?"
 * Defaultr walks the spec and asks "Does this exist in the data?  If not, add it."
 *
 * Example : Given input Json like
 * <pre>
 * {
 *   "Rating":3,
 *   "SecondaryRatings":{
 *      "quality":{
 *         "Range":7,
 *         "Value":3,
 *         "Id":"quality"
 *      },
 *      "sharpness": {
 *         "Value":4,
 *         "Id":"sharpness"
 *      }
 *   }
 * }
 * </pre>
 * With the desired output being :
 * <pre>
 * {
 *   "Rating":3,
 *   "RatingRange" : 5,
 *   "SecondaryRatings":{
 *      "quality":{
 *         "Range":7,
 *         "Value":3,
 *         "Id":"quality",
 *         "ValueLabel": null,
 *         "Label": null,
 *         "MaxLabel": "Great",
 *         "MinLabel": "Terrible",
 *         "DisplayType": "NORMAL"
 *      },
 *      "sharpness": {
 *         "Range":5,
 *         "Value":4,
 *         "Id":"sharpness",
 *         "ValueLabel": null,
 *         "Label": null,
 *         "MaxLabel": "High",
 *         "MinLabel": "Low",
 *         "DisplayType": "NORMAL"
 *      }
 *   }
 * }
 * </pre>
 * This is what the Defaultr Spec would look like
 * <pre>
 * {
 *   "RatingRange" : 5,
 *   "SecondaryRatings": {
 *     "quality|value" : {
 *        "ValueLabel": null,
 *        "Label": null,
 *        "MaxLabel": "Great",
 *        "MinLabel": "Terrible",
 *        "DisplayType": "NORMAL"
 *
 *     }
 *     "*": {
 *        "Range" : 5,
 *        "ValueLabel": null,
 *        "Label": null,
 *        "MaxLabel": "High",
 *        "MinLabel": "Low",
 *        "DisplayType": "NORMAL"
 *     }
 *   }
 * }
 * </pre>
 *
 * The Spec file format for Defaulr a tree Map<String, Object> objects.   Defaultr handles outputing
 *  of Json Arrays via special wildcard in the Spec.
 *
 * Defaltr Spec WildCards and Flag :
 * "*" aka STAR : Apply these defaults to all input keys at this level
 * "|" aka OR  : Apply these defaults to input keys, if they exist
 * "[]" aka : Signal to Defaultr that the data for this key should be an array.
 *   This means all defaultr keys below this entry have to be "integers".
 *
 * Valid Array Specification :
 * <pre>
 * {
 *   "photos[]" : {
 *     "2" : {
 *       "url" : "http://www.bazaarvoice.com",
 *       "caption" : ""
 *     }
 *   }
 * }
 * </pre>
 *
 * An Invalid Array Specification would be :
 * <pre>
 * {
 *   "photos[]" : {
 *     "photo-id-1234" : {
 *       "url" : "http://www.bazaarvoice.com",
 *       "caption" : ""
 *     }
 *   }
 * }
 * </pre>
 *
 * Algorithm
 * Defaultr walks its Spec in a depth first way.
 * At each level in the Spec tree, Defaultr, applies Spec from most specific to least specific :
 *   Literals key values
 *   "|"
 *   "*"
 *
 * At a given level in the Defaultr Spec tree, only literal keys force Defaultr to create new entries
 *  in the input data: either as a single literal value or adding new nested Array or Map objects.
 * The wildcard operators, are applied after the literal keys, and will not cause the those keys to be
 *  added if they are not already present in the input document (either naturally or having been defaulted
 *  in from literal spec keys).
 *
 *
 * Algorithm :
 * 1) Walk the spec
 * 2) for each literal key in the spec (specKey)
 * 2.1) if the the specKey is a map or array, and the input is null, default an empty Map or Array into the output
 * 2.2.1) re-curse on the literal spec
 * 2.2) if the the specKey is a map or array, and the input is not null, but of the "wrong" type, skip and do not recurse
 * 2.2) if the the specKey, is a literal value, default the literal and value into the output and do not recurse
 * 3) for each wildcard in the spec
 * 3.1) find all keys from the defaultee that match the wildcard
 * 3.2) treat each key as a literal speckey
 */
public class Defaultr implements Chainable {

    public interface WildCards {
        public static final String STAR = "*";
        public static final String OR = "|";
        public static final String ARRAY = "[]";
    }

    private DefaultrKeyComparator keyComparator = new DefaultrKeyComparator();

    /**
     * Applies a Defaultr transform for Chainr
     *
     * @param input the JSON object to transform
     * @param operationEntry the JSON object from the Chainr spec containing
     *  the rest of the details necessary to carry out the transform (specifically,
     *  in this case, a defaultr spec)
     * @return the output object with defaults applied to it
     * @throws JoltException for a malformed spec or if there are issues
     */
    @Override
    public Object process( Object input, Map<String, Object> operationEntry ) throws JoltException {
        Object spec = operationEntry.get( "spec" );
        if (spec == null) {
            throw new JoltException( "JOLT Defaultr expected a spec in its operation entry, but instead got: " + operationEntry.toString() );
        }
        return defaultr( operationEntry.get( "spec" ), input);
    }

    /**
     * Top level standalone Defaultr method.
     *
     * @param spec Defaultr spec
     * @param defaultee Json object to have defaults applied to.  This will be modifed.
     * @return the modifed defaultee
     */
    public Object defaultr( Object spec, Object defaultee ) {

        // TODO : Make copy of the defaultee?
        Map<DefaultrKey, Object> keyedSpec = DefaultrKey.parseSpec( (Map<String, Object>) spec );

        // Setup to call the recursive method
        DefaultrKey root = new DefaultrKey( false, "root" );
        if ( defaultee == null ) {
            defaultee = createDefaultContainerObject(root);
        }

        // Defaultr works by looking one level down the tree, hence we need to pass in a root and a valid defaultee
        this.applySpec( root, keyedSpec, defaultee );

        return defaultee;
    }

    /**
     * This is the main "recursive" method.   The parentKey and the spce are never null, in that we don't recurse
     *  if spec !instanceof Map.  The only time defaultee is null, is if there is a mismatch between the data and the
     *  spec wrt Array vs Map.
     *
     *  SPECTULATIVE TODO : It might be possible to get rid of the complexity of defaulting into Maps vs Arrays, by
     *   translating arrays into Maps, applying Default logic, and then translating back into an array.   This
     *   could be facilitated by having this method returned a constructed object, which the parent stack frame
     *   could translate as needed.
     */
    private void applySpec( DefaultrKey parentKey, Map<DefaultrKey, Object> spec, Object defaultee ) {

        if ( parentKey.isArrayOutput ) {
            ensureArraySize( parentKey.maxLiteralKey, defaultee );
        }

        // Find and sort the children DefaultrKeys : literals, |, then *
        ArrayList<DefaultrKey> sortedChildren = new ArrayList<DefaultrKey>();
        sortedChildren.addAll( spec.keySet() );
        Collections.sort( sortedChildren, keyComparator );

        for ( DefaultrKey childeKey : sortedChildren ) {
            applyDefaultrKey( childeKey, spec.get( childeKey ), defaultee );
        }
    }

    private void ensureArraySize( int maxDefaultKey, Object defaultObj ) {

        if ( defaultObj instanceof List) {

            List<Object> defaultee = (List<Object>) defaultObj;

            // extend the defaultee list if needed
            for ( int index = defaultee.size() - 1; index < maxDefaultKey; index++ ) {
                defaultee.add( null );
            }
        }
    }

    private void applyDefaultrKey( DefaultrKey childKey, Object subSpec, Object defaultee ) {

        // Find all defaultee keys that match the childKey spec.  Simple for Literal keys, more work for * and |.
        Collection literalKeys = this.findMatchingDefaulteeKeys( childKey, defaultee );

        if ( childKey.isArrayKey && defaultee instanceof List ) {
            for ( Object literalKey : literalKeys ) {
                this.defaultLiteralValue( (Integer) literalKey, childKey, subSpec, (List<Object>) defaultee );
            }
        }
        else if ( !childKey.isArrayKey && defaultee instanceof Map ) {
            for ( Object literalKey : literalKeys ) {
                this.defaultLiteralValue( (String) literalKey, childKey, subSpec, (Map<String, Object>) defaultee );
            }
        }
        // Else defaultee was not a container object, the wrong type of container object, or null
        //  net effect, we couldn't push values into it
    }

    /**
     * Default a literal value into a List.
     */
    private void defaultLiteralValue( Integer literalIndex, DefaultrKey dkey, Object subSpec, List<Object> defaultee ) {

        Object defaulteeValue = defaultee.get( literalIndex );

        if ( subSpec instanceof Map ) {
            if ( defaulteeValue == null ) {
                defaulteeValue = createDefaultContainerObject( dkey );
                defaultee.set( literalIndex, defaulteeValue ); // make a new Array in the output
            }

            // Re-curse into subspec
            this.applySpec( dkey, (Map<DefaultrKey, Object>) subSpec, defaulteeValue );
        } else {
            if ( defaulteeValue == null ) {
                defaultee.set( literalIndex, subSpec );  // apply a default value into a List, assumes the list as already been expanded if needed.
            }
        }
    }

    /**
     * Default into a Map
     */
    private void defaultLiteralValue( String literalKey, DefaultrKey dkey, Object subSpec, Map<String, Object> defaultee ) {

        Object defaulteeValue = defaultee.get( literalKey );

        if ( subSpec instanceof Map ) {
            if ( defaulteeValue == null ) {
                defaulteeValue = createDefaultContainerObject( dkey );
                defaultee.put( literalKey, defaulteeValue );  // make a new map in the output
            }

            // Re-curse into subspec
            this.applySpec( dkey, (Map<DefaultrKey, Object>) subSpec, defaulteeValue );
        } else {
            if ( defaulteeValue == null ) {
                defaultee.put( literalKey, subSpec );  // apply a default value into a map

            }
        }
    }


    private Collection findMatchingDefaulteeKeys( DefaultrKey key, Object defaultee ) {

        if ( defaultee == null ) {
            return Collections.emptyList();
        }

        switch ( key.op ) {
            // If the Defaultee is not null, it should get these literal values added to it
            case LITERAL:
                return key.getKeyValues();
            // If the Defaultee is not null, identify all its keys
            case STAR:
                if ( !key.isArrayKey && defaultee instanceof Map ) {
                    return ( (Map) defaultee ).keySet();
                }
                else if ( key.isArrayKey && defaultee instanceof List ) {
                    // this assumes the defaultee list has already been expanded to the right size
                    List defaultList = (List) defaultee;
                    List<Integer> allIndexes = new ArrayList<Integer>( defaultList.size() );
                    for ( int index = 0; index < defaultList.size(); index++ ) {
                        allIndexes.add( index );
                    }

                    return allIndexes;
                }
                break;
            // If the Defaultee is not null, identify the intersection between its keys and the OR values
            case OR:
                if ( !key.isArrayKey && defaultee instanceof Map ) {

                    Set<String> intersection = new HashSet<String>( ( (Map) defaultee ).keySet() );
                    intersection.retainAll( key.getKeyValues() );
                    return intersection;
                }
                else if ( key.isArrayKey && defaultee instanceof List ) {

                    List<Integer> indexesInRange = new ArrayList<Integer>();
                    for ( Object orValue : key.getKeyValues() ) {
                        if ( (Integer) orValue < ( (List) defaultee ).size() ) {
                            indexesInRange.add( (Integer) orValue );
                        }
                    }
                    return indexesInRange;
                }
                break;
        }

        return Collections.emptyList();
    }

    private Object createDefaultContainerObject( DefaultrKey dkey ) {
        if ( dkey.isArrayOutput ) {
            return new ArrayList<Object>();
        } else {
            return new LinkedHashMap<String, Object>();
        }
    }
}