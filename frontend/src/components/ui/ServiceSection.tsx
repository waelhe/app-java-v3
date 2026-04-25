'use client';

import React, { useState, useEffect, useRef } from 'react';
import Link from 'next/link';
import { ChevronLeft, ChevronRight, ArrowLeft, ArrowRight, Loader2 } from 'lucide-react';
import ServiceCard, { Listing } from './ServiceCard';
import { useLanguage } from '@/contexts/LanguageContext';
import { Skeleton } from '@/components/ui/skeleton';

// بيانات تجريبية للعرض
const SAMPLE_LISTINGS: Listing[] = [
  {
    id: '1',
    title: 'شقة فاخرة في باب توما',
    slug: 'luxury-apartment-bab-toma',
    type: 'apartment',
    city: 'دمشق',
    country: 'سوريا',
    basePrice: 150000,
    currency: 'SYP',
    capacity: 4,
    bedrooms: 2,
    bathrooms: 1,
    ratingAverage: 4.8,
    ratingCount: 24,
    featured: true,
    images: [{ id: '1', url: 'https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?auto=format&fit=crop&w=300&q=80', isPrimary: true }],
    amenities: [{ name: 'واي فاي' }, { name: 'مكيف' }],
    host: { firstName: 'أحمد', lastName: 'محمد' }
  },
  {
    id: '2',
    title: 'غرفة فندقية - فندق الشام',
    slug: 'hotel-room-al-sham',
    type: 'hotel',
    city: 'دمشق',
    country: 'سوريا',
    basePrice: 200000,
    currency: 'SYP',
    capacity: 2,
    bedrooms: 1,
    bathrooms: 1,
    ratingAverage: 4.5,
    ratingCount: 56,
    featured: false,
    images: [{ id: '2', url: 'https://images.unsplash.com/photo-1566073771259-6a8506099945?auto=format&fit=crop&w=300&q=80', isPrimary: true }],
    amenities: [{ name: 'إفطار' }, { name: 'مسبح' }],
    host: { firstName: 'فندق', lastName: 'الشام' }
  },
  {
    id: '3',
    title: 'شقة عصرية في حلب الجديدة',
    slug: 'modern-apartment-aleppo',
    type: 'apartment',
    city: 'حلب',
    country: 'سوريا',
    basePrice: 120000,
    currency: 'SYP',
    capacity: 6,
    bedrooms: 3,
    bathrooms: 2,
    ratingAverage: 4.9,
    ratingCount: 18,
    featured: true,
    images: [{ id: '3', url: 'https://images.unsplash.com/photo-1522708323590-d24dbb6b0267?auto=format&fit=crop&w=300&q=80', isPrimary: true }],
    amenities: [{ name: 'موقف سيارات' }, { name: 'واي فاي' }],
    host: { firstName: 'سارة', lastName: 'العلي' }
  },
  {
    id: '4',
    title: 'فيلا فاخرة - طرطوس',
    slug: 'luxury-villa-tartous',
    type: 'villa',
    city: 'طرطوس',
    country: 'سوريا',
    basePrice: 350000,
    currency: 'SYP',
    capacity: 8,
    bedrooms: 4,
    bathrooms: 3,
    ratingAverage: 4.7,
    ratingCount: 12,
    featured: false,
    images: [{ id: '4', url: 'https://images.unsplash.com/photo-1580587771525-78b9dba3b914?auto=format&fit=crop&w=300&q=80', isPrimary: true }],
    amenities: [{ name: 'حديقة' }, { name: 'مسبح خاص' }],
    host: { firstName: 'محمد', lastName: 'حسن' }
  },
  {
    id: '5',
    title: 'شقة دوبلكس - المزة',
    slug: 'duplex-mezzeh',
    type: 'apartment',
    city: 'دمشق',
    country: 'سوريا',
    basePrice: 180000,
    currency: 'SYP',
    capacity: 5,
    bedrooms: 2,
    bathrooms: 2,
    ratingAverage: 4.6,
    ratingCount: 32,
    featured: false,
    images: [{ id: '5', url: 'https://images.unsplash.com/photo-1512917774080-9991f1c4c750?auto=format&fit=crop&w=300&q=80', isPrimary: true }],
    amenities: [{ name: 'واي فاي' }, { name: 'مكيف' }],
    host: { firstName: 'ليلى', lastName: 'أحمد' }
  },
  {
    id: '6',
    title: 'عشة تقليدية - تدمر',
    slug: 'traditional-house-palmyra',
    type: 'house',
    city: 'تدمر',
    country: 'سوريا',
    basePrice: 100000,
    currency: 'SYP',
    capacity: 4,
    bedrooms: 2,
    bathrooms: 1,
    ratingAverage: 4.4,
    ratingCount: 8,
    featured: false,
    images: [{ id: '6', url: 'https://images.unsplash.com/photo-1564013799919-ab600027ffc6?auto=format&fit=crop&w=300&q=80', isPrimary: true }],
    amenities: [{ name: 'مواقد' }],
    host: { firstName: 'خالد', lastName: 'البادية' }
  }
];

interface ServiceSectionProps {
  title: string;
  subtitle?: string;
  categoryId?: string;
  filterPopular?: boolean;
  limit?: number;
}

export default function ServiceSection({ 
  title, 
  subtitle, 
  categoryId, 
  filterPopular = false, 
  limit = 8,
}: ServiceSectionProps) {
  const { language } = useLanguage();
  const [listings, setListings] = useState<Listing[]>([]);
  const [loading, setLoading] = useState(true);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const fetchListings = async () => {
      try {
        setLoading(true);
        
        const params = new URLSearchParams();
        params.append('limit', limit.toString());
        params.append('status', 'active');
        
        if (categoryId) {
          params.append('category', categoryId);
        }
        
        const response = await fetch(`/api/listings?${params.toString()}`);
        
        if (!response.ok) {
          throw new Error('Failed to fetch listings');
        }
        
        const data = await response.json();
        let fetchedListings = data.data || [];
        
        // Sort by rating if filterPopular is true
        if (filterPopular) {
          fetchedListings = fetchedListings.sort((a: Listing, b: Listing) => {
            const ratingA = a.ratingAverage || 0;
            const ratingB = b.ratingAverage || 0;
            return ratingB - ratingA;
          });
        }
        
        // إذا لم توجد بيانات، استخدم البيانات التجريبية
        if (fetchedListings.length === 0) {
          setListings(SAMPLE_LISTINGS.slice(0, limit));
        } else {
          setListings(fetchedListings);
        }
      } catch (error) {
        console.error('Error fetching listings for section:', error);
        // استخدام البيانات التجريبية عند الخطأ
        setListings(SAMPLE_LISTINGS.slice(0, limit));
      } finally {
        setLoading(false);
      }
    };

    fetchListings();
  }, [categoryId, filterPopular, limit]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      const { scrollLeft, clientWidth } = scrollRef.current;
      const scrollTo = direction === 'left' 
        ? scrollLeft - clientWidth * 0.8 
        : scrollLeft + clientWidth * 0.8;
      
      scrollRef.current.scrollTo({ left: scrollTo, behavior: 'smooth' });
    }
  };

  if (!loading && listings.length === 0) return null;

  return (
    <section className="pt-0 pb-6 bg-white overflow-hidden">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        {/* Section Header */}
        <div className="flex justify-between items-end mb-4">
          <div className="flex-1">
            <h2 className="text-xl md:text-2xl font-bold text-gray-900 leading-tight">
              {title}
            </h2>
            {subtitle && <p className="text-sm text-gray-500 mt-1 max-w-2xl">{subtitle}</p>}
          </div>

          <div className="flex items-center gap-3">
            {categoryId && (
              <Link 
                href={`/?category=${categoryId}`} 
                className="p-2 hover:bg-gray-100 rounded-full transition-colors hidden sm:block"
              >
                {language === 'ar' ? <ArrowLeft className="w-5 h-5 text-gray-900" /> : <ArrowRight className="w-5 h-5 text-gray-900" />}
              </Link>
            )}
            <div className="flex items-center gap-1">
              <button 
                onClick={() => scroll('right')}
                className="p-2 rounded-full border border-gray-200 hover:bg-gray-50 transition-colors shadow-sm bg-white"
              >
                <ChevronRight className="w-4 h-4 text-gray-600" />
              </button>
              <button 
                onClick={() => scroll('left')}
                className="p-2 rounded-full border border-gray-200 hover:bg-gray-50 transition-colors shadow-sm bg-white"
              >
                <ChevronLeft className="w-4 h-4 text-gray-600" />
              </button>
            </div>
          </div>
        </div>

        {loading ? (
          <div className="flex gap-4 md:gap-6 overflow-hidden pb-4">
            {[1, 2, 3, 4].map((i) => (
              <div key={i} className="min-w-[280px] md:min-w-[320px]">
                <div className="aspect-square rounded-xl bg-gray-100 mb-2">
                  <Skeleton className="w-full h-full" />
                </div>
                <div className="space-y-2">
                  <Skeleton className="h-4 w-3/4" />
                  <Skeleton className="h-3 w-1/2" />
                  <Skeleton className="h-4 w-1/3" />
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div 
            ref={scrollRef}
            className="flex gap-4 md:gap-6 overflow-x-auto pb-4 snap-x snap-mandatory hide-scrollbar"
          >
            {listings.map((listing) => (
              <div key={listing.id} className="snap-start">
                <ServiceCard 
                  listing={listing}
                  activeColorClass="bg-emerald-600 text-white"
                  activeTextClass="text-emerald-600"
                  activeBgLightClass="bg-emerald-50"
                />
              </div>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
