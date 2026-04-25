'use client';

import React, { useState, useEffect } from 'react';
import Link from 'next/link';
import { useLanguage } from '@/contexts/LanguageContext';
import { ChevronLeft, ChevronRight } from 'lucide-react';

// صور الهيرو التي تمثل أقسام الموقع
const HERO_SLIDES = [
  {
    id: 1,
    image: 'https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?w=1920&q=80',
    titleAr: 'اكتشف أفضل المطاعم والمقاهي',
    titleEn: 'Discover the Best Restaurants & Cafes',
    subtitleAr: 'تجارب طعام مميزة في منطقتك',
    subtitleEn: 'Unique dining experiences in your area',
    link: '/restaurants',
    category: 'مطاعم ومقاهي',
  },
  {
    id: 2,
    image: 'https://images.unsplash.com/photo-1629909613654-28e377c37b09?w=1920&q=80',
    titleAr: 'خدمات صحية وطبية متميزة',
    titleEn: 'Outstanding Health & Medical Services',
    subtitleAr: 'أطباء، صيدليات، ومراكز طبية قريبة منك',
    subtitleEn: 'Doctors, pharmacies, and medical centers near you',
    link: '/doctors',
    category: 'صحة وطبية',
  },
  {
    id: 3,
    image: 'https://images.unsplash.com/photo-1560518883-ce09059eeffa?w=1920&q=80',
    titleAr: 'عقارات للبيع والإيجار',
    titleEn: 'Real Estate for Sale & Rent',
    subtitleAr: 'شقق، فلل، ومكاتب في أفضل المواقع',
    subtitleEn: 'Apartments, villas, and offices in prime locations',
    link: '/real-estate',
    category: 'عقارات',
  },
  {
    id: 4,
    image: 'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?w=1920&q=80',
    titleAr: 'حرفيون ومهنيون محترفون',
    titleEn: 'Professional Craftsmen & Skilled Workers',
    subtitleAr: 'سباكة، كهرباء، نجارة، والمزيد',
    subtitleEn: 'Plumbing, electrical, carpentry, and more',
    link: '/craftsmen',
    category: 'خدمات',
  },
  {
    id: 5,
    image: 'https://images.unsplash.com/photo-1441986300917-64674bd600d8?w=1920&q=80',
    titleAr: 'أسواق ومتاجر متنوعة',
    titleEn: 'Diverse Markets & Shops',
    subtitleAr: 'كل ما تحتاجه من أسواق محلية ومتاجر',
    subtitleEn: 'Everything you need from local markets and shops',
    link: '/markets',
    category: 'أسواق وتسوق',
  },
  {
    id: 6,
    image: 'https://images.unsplash.com/photo-1521737711867-e3b97375f902?w=1920&q=80',
    titleAr: 'وظائف وفرص عمل',
    titleEn: 'Jobs & Career Opportunities',
    subtitleAr: 'ابحث عن وظيفتك القادمة هنا',
    subtitleEn: 'Find your next career opportunity here',
    link: '/jobs',
    category: 'وظائف',
  },
];

export default function Hero() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const [currentSlide, setCurrentSlide] = useState(0);
  const [isTransitioning, setIsTransitioning] = useState(false);

  // التبديل التلقائي بين الشرائح
  useEffect(() => {
    const timer = setInterval(() => {
      setIsTransitioning(true);
      setTimeout(() => {
        setCurrentSlide((prev) => (prev + 1) % HERO_SLIDES.length);
        setIsTransitioning(false);
      }, 300);
    }, 5000); // تبديل كل 5 ثواني

    return () => clearInterval(timer);
  }, []);

  const goToSlide = (index: number) => {
    setIsTransitioning(true);
    setTimeout(() => {
      setCurrentSlide(index);
      setIsTransitioning(false);
    }, 150);
  };

  const nextSlide = () => {
    goToSlide((currentSlide + 1) % HERO_SLIDES.length);
  };

  const prevSlide = () => {
    goToSlide((currentSlide - 1 + HERO_SLIDES.length) % HERO_SLIDES.length);
  };

  const slide = HERO_SLIDES[currentSlide];

  return (
    <section className="group relative w-full h-[350px] sm:h-[400px] md:h-[500px] lg:h-[550px] overflow-hidden mt-[96px]">
      {/* صور الخلفية المتغيرة */}
      {HERO_SLIDES.map((s, index) => (
        <div
          key={s.id}
          className={`absolute inset-0 transition-opacity duration-700 ease-in-out ${
            index === currentSlide && !isTransitioning ? 'opacity-100' : 'opacity-0'
          }`}
        >
          <div 
            className="absolute inset-0 bg-cover bg-center"
            style={{ backgroundImage: `url('${s.image}')` }}
          />
        </div>
      ))}
      
      {/* overlay داكن لقراءة النص */}
      <div className="absolute inset-0 bg-gradient-to-r from-black/80 via-black/60 to-black/40" />

      {/* المحتوى */}
      <div className="relative z-10 h-full flex flex-col justify-center px-4 sm:px-6 md:px-12 lg:px-20 max-w-7xl mx-auto">
        
        {/* عنوان الفئة */}
        <div 
          className={`transition-all duration-500 ${
            isTransitioning ? 'opacity-0 translate-y-2' : 'opacity-100 translate-y-0'
          }`}
        >
          <span className="inline-block px-3 py-1 mb-4 text-xs font-bold text-white bg-red-500/90 rounded-full">
            {slide.category}
          </span>

          {/* العنوان الرئيسي */}
          <h1 className="text-2xl sm:text-3xl md:text-4xl lg:text-5xl xl:text-6xl font-bold text-white max-w-2xl leading-tight mb-3 md:mb-4">
            {isArabic ? slide.titleAr : slide.titleEn}
          </h1>

          {/* الوصف */}
          <p className="text-base sm:text-lg md:text-xl text-white/80 max-w-xl mb-6 md:mb-8">
            {isArabic ? slide.subtitleAr : slide.subtitleEn}
          </p>

          {/* زر الاستكشاف */}
          <Link
            href={slide.link}
            className="inline-flex items-center gap-2 px-5 sm:px-6 py-2.5 sm:py-3 bg-red-500 hover:bg-red-600 text-white font-bold rounded-lg transition-all duration-300 shadow-lg hover:shadow-xl transform hover:-translate-y-0.5"
          >
            <svg className="w-4 h-4 sm:w-5 sm:h-5" fill="currentColor" viewBox="0 0 24 24">
              <path d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/>
            </svg>
            <span className="text-sm sm:text-base">{isArabic ? 'استكشف الآن' : 'Explore Now'}</span>
          </Link>
        </div>

        {/* مؤشرات الشرائح */}
        <div className="absolute bottom-6 left-1/2 -translate-x-1/2 flex items-center gap-2 sm:gap-3 z-20">
          {HERO_SLIDES.map((_, index) => (
            <button
              key={index}
              onClick={() => goToSlide(index)}
              className={`h-1.5 sm:h-2 rounded-full transition-all duration-300 ${
                index === currentSlide 
                  ? 'w-8 sm:w-10 bg-red-500' 
                  : 'w-1.5 sm:w-2 bg-white/50 hover:bg-white/70'
              }`}
              aria-label={`Go to slide ${index + 1}`}
            />
          ))}
        </div>

        {/* مؤشر الموقع */}
        <div className="absolute bottom-6 right-4 sm:right-12 text-white/60 text-xs sm:text-sm hidden sm:block z-20">
          <p>{isArabic ? 'نبض الضاحية وقدسيا' : 'Nabd Dahia & Qudsaya'}</p>
        </div>
      </div>

      {/* مناطق النقر للتنقل - تأخذ نصف المساحة يمين ويسار */}
      <button
        onClick={prevSlide}
        className="absolute left-0 top-0 w-1/4 h-full z-10 cursor-pointer group/btn"
        aria-label="Previous slide"
      >
        <span className="absolute left-4 top-1/2 -translate-y-1/2 w-10 h-10 flex items-center justify-center rounded-full bg-white/0 group-hover:bg-white/20 text-white/0 group-hover:text-white transition-all duration-300">
          <ChevronLeft className="w-6 h-6" />
        </span>
      </button>
      <button
        onClick={nextSlide}
        className="absolute right-0 top-0 w-1/4 h-full z-10 cursor-pointer group/btn"
        aria-label="Next slide"
      >
        <span className="absolute right-4 top-1/2 -translate-y-1/2 w-10 h-10 flex items-center justify-center rounded-full bg-white/0 group-hover:bg-white/20 text-white/0 group-hover:text-white transition-all duration-300">
          <ChevronRight className="w-6 h-6" />
        </span>
      </button>
    </section>
  );
}
